package com.transferservice.transfer_processing_service.worker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestTransfers;
import com.transferservice.transfer_processing_service.messaging.MessagingTopology;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEvent;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import com.transferservice.transfer_processing_service.worker.ProcessingCheckpoints.Checkpoint;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * The worker's debit step against the mock Fineract.
 */
class DebitProcessingTest extends WorkerTestSupport {

	/** Mandatory scenario 1: twenty simultaneous identical requests, one transfer, one committed debit. */
	@Test
	void twentySimultaneousIdenticalRequestsProduceOneDebit() throws Exception {
		Map<String, Object> request = TestTransfers.request("dup-" + UUID.randomUUID(), this.sourceAccount);
		List<TransferResponse> responses = concurrently(20,
				() -> TestTransfers.post(this.client, this.partner, request).getBody());

		String reference = responses.get(0).reference();
		assertThat(responses).extracting(TransferResponse::reference).containsOnly(reference);
		awaitState(reference, TransferState.COMPLETED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("75000.00");
	}

	/** Mandatory scenario 2: two simultaneous transfers cannot spend more than the available balance. */
	@Test
	void twoSimultaneousTransfersCannotOverspend() throws Exception {
		TestPartners.fund(this.jdbc, this.sourceAccount, "30000.00");

		List<TransferResponse> responses = concurrently(2, () -> TestTransfers.post(this.client, this.partner,
				TestTransfers.request("spend-" + UUID.randomUUID(), this.sourceAccount)).getBody());

		List<Transfer> settled = responses.stream()
			.map((r) -> awaitAnyState(r.reference(), TransferState.COMPLETED, TransferState.DEBIT_FAILED))
			.toList();
		assertThat(settled).extracting(Transfer::getState)
			.containsExactlyInAnyOrder(TransferState.COMPLETED, TransferState.DEBIT_FAILED);
		assertThat(settled).filteredOn((t) -> t.getState() == TransferState.DEBIT_FAILED)
			.singleElement()
			.satisfies((t) -> assertThat(t.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS"));
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("5000.00");
	}

	/** Mandatory scenario 3: the debit commits but the response times out; recovery confirms it by lookup. */
	@Test
	void debitCommittedBeforeTimeoutIsConfirmedWithoutSecondDebit() {
		this.client.post().uri("/mock/fineract/faults").body(Map.of("count", 1, "delayMs", 1500)).retrieve()
			.toBodilessEntity();
		long debitCallsBefore = debitRequests();

		TransferResponse transfer = accept();

		awaitState(transfer.reference(), TransferState.COMPLETED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		// One debit call (which timed out); the outcome was then learned by lookup, not by resending.
		assertThat(debitRequests() - debitCallsBefore).isEqualTo(1);
		assertThat(eventTypes(transfer.reference())).containsSubsequence("DEBIT_REQUESTED", "DEBIT_OUTCOME_UNKNOWN",
				"DEBIT_CONFIRMED");
		assertThat(event(transfer.reference(), "DEBIT_CONFIRMED").getDetail()).containsEntry("source", "lookup");
	}

	/** Mandatory scenario 4: the worker dies after the debit, before saving it; a replacement recovers. */
	@Test
	void workerCrashAfterDebitIsRecoveredByReplacementWorker() {
		doThrow(new IllegalStateException("simulated worker crash")).doNothing()
			.when(this.checkpoints)
			.reached(eq(Checkpoint.AFTER_DEBIT_CALL), any());
		long debitCallsBefore = debitRequests();

		TransferResponse transfer = accept();

		awaitState(transfer.reference(), TransferState.COMPLETED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(debitRequests() - debitCallsBefore).isEqualTo(1);
		TransferEvent requested = event(transfer.reference(), "DEBIT_REQUESTED");
		TransferEvent confirmed = event(transfer.reference(), "DEBIT_CONFIRMED");
		assertThat(confirmed.getDetail()).containsEntry("source", "lookup");
		// Confirmed by a different processing attempt than the one that crashed.
		assertThat(confirmed.getActor()).isNotEqualTo(requested.getActor());
	}

	/** Mandatory scenario 7 (redelivery half): repeated messages create no additional debits or payments. */
	@Test
	void redeliveredMessagesCreateNoAdditionalEffects() throws Exception {
		TransferResponse transfer = accept();
		awaitState(transfer.reference(), TransferState.COMPLETED);
		long debitCallsBefore = debitRequests();
		long submissionsBefore = paymentSubmissions();
		Transfer stored = transfer(transfer.reference());

		for (int i = 0; i < 5; i++) {
			this.rabbit.send(MessagingTopology.EXCHANGE, MessagingTopology.PROCESS_ROUTING_KEY,
					MessageBuilder.withBody(("{\"transferId\":\"" + stored.getId() + "\"}").getBytes())
						.setContentType(MessageProperties.CONTENT_TYPE_JSON)
						.build());
		}

		Thread.sleep(1500);
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.COMPLETED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(providerPayments(transfer.reference())).isEqualTo(1);
		assertThat(debitRequests()).isEqualTo(debitCallsBefore);
		assertThat(paymentSubmissions()).isEqualTo(submissionsBefore);
	}

	@Test
	void unknownSourceAccountInFineractFailsWithoutMovingMoney() {
		String unfunded = TestTransfers.randomAccount();
		TestPartner other = TestPartners.create(this.jdbc, unfunded);

		TransferResponse transfer = TestTransfers.post(this.client, other,
				TestTransfers.request("unfunded-" + UUID.randomUUID(), unfunded)).getBody();

		await().atMost(Duration.ofSeconds(15)).until(() -> this.transfers
			.findByPartnerIdAndReference(other.id(), transfer.reference()).orElseThrow()
			.getState() == TransferState.DEBIT_FAILED);
		assertThat(this.transfers.findByPartnerIdAndReference(other.id(), transfer.reference()).orElseThrow()
			.getFailureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
	}

}
