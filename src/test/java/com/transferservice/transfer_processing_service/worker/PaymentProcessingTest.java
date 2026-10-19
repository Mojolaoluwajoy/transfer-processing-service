package com.transferservice.transfer_processing_service.worker;

import com.transferservice.transfer_processing_service.messaging.MessagingTopology;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEvent;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import com.transferservice.transfer_processing_service.worker.ProcessingCheckpoints.Checkpoint;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * The worker's payment step against the mock provider, driven by SIM: narration tokens.
 */
class PaymentProcessingTest extends WorkerTestSupport {

	@Test
	void successfulPaymentCompletesTransfer() {
		TransferResponse transfer = accept();

		awaitState(transfer.reference(), TransferState.COMPLETED);
		FinancialOperation payment = operation(transfer.reference(), FinancialOperation.Type.PAYMENT);
		assertThat(payment.getStatus()).isEqualTo(FinancialOperation.Status.SUCCEEDED);
		assertThat(payment.getOperationReference()).isEqualTo("PAY-" + transfer.reference());
		assertThat(payment.getExternalId()).isNotBlank();
		assertThat(eventTypes(transfer.reference())).containsSubsequence("DEBIT_CONFIRMED", "PAYMENT_REQUESTED",
				"PAYMENT_CONFIRMED");
		assertThat(providerPayments(transfer.reference())).isEqualTo(1);
	}

	@Test
	void confirmedPaymentFailureLeadsToRefund() {
		TransferResponse transfer = accept("Invoice SIM:FAIL");

		awaitState(transfer.reference(), TransferState.REFUNDED);
		assertThat(transfer(transfer.reference()).getFailureReason()).isEqualTo("SIMULATED_FAILURE");
		assertThat(operation(transfer.reference(), FinancialOperation.Type.PAYMENT).getStatus())
			.isEqualTo(FinancialOperation.Status.FAILED);
	}

	/**
	 * Mandatory scenario 8: a provider timeout leaves the payment unresolved until
	 * authoritative evidence (here, a status lookup after the provider settles) is available.
	 */
	@Test
	void providerTimeoutLeavesPaymentUnresolvedUntilEvidence() throws Exception {
		long submissionsBefore = paymentSubmissions();
		TransferResponse transfer = accept("Invoice SIM:PENDING SIM:SLOW_RESPONSE");

		awaitEvent(transfer.reference(), "PAYMENT_OUTCOME_UNKNOWN");
		awaitEvent(transfer.reference(), "PAYMENT_ACCEPTED");
		// Several polls later, still pending: no failure, no refund, money still debited.
		Thread.sleep(2000);
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.PAYMENT_PENDING);
		assertThat(this.operations.findByTransferIdAndOperationType(transfer(transfer.reference()).getId(),
				FinancialOperation.Type.REFUND)).isEmpty();
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("75000.00");

		resolvePayment(transfer.reference(), "SUCCEEDED", false);

		awaitState(transfer.reference(), TransferState.COMPLETED);
		assertThat(event(transfer.reference(), "PAYMENT_CONFIRMED").getDetail()).containsEntry("source", "lookup");
		// The timed-out submission was never resent: its outcome was learned by lookup.
		assertThat(paymentSubmissions() - submissionsBefore).isEqualTo(1);
		assertThat(providerPayments(transfer.reference())).isEqualTo(1);
	}

	@Test
	void pendingPaymentFailsOnlyWhenProviderConfirmsFailure() throws Exception {
		TransferResponse transfer = accept("Invoice SIM:PENDING");
		awaitEvent(transfer.reference(), "PAYMENT_ACCEPTED");
		Thread.sleep(1000);
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.PAYMENT_PENDING);

		resolvePayment(transfer.reference(), "FAILED", false);

		awaitState(transfer.reference(), TransferState.REFUNDED);
		assertThat(eventTypes(transfer.reference())).containsSubsequence("PAYMENT_ACCEPTED", "PAYMENT_FAILED",
				"REFUND_CONFIRMED");
	}

	@Test
	void workerCrashAfterPaymentSubmissionIsRecoveredByLookup() {
		doThrow(new IllegalStateException("simulated worker crash")).doNothing()
			.when(this.checkpoints)
			.reached(eq(Checkpoint.AFTER_PAYMENT_CALL), any());
		long submissionsBefore = paymentSubmissions();

		// No callback, so the outcome can only come from the replacement worker's lookup.
		TransferResponse transfer = accept("Invoice SIM:NO_CALLBACK");

		awaitState(transfer.reference(), TransferState.COMPLETED);
		TransferEvent requested = event(transfer.reference(), "PAYMENT_REQUESTED");
		TransferEvent confirmed = event(transfer.reference(), "PAYMENT_CONFIRMED");
		assertThat(confirmed.getDetail()).containsEntry("source", "lookup");
		assertThat(confirmed.getActor()).isNotEqualTo(requested.getActor());
		assertThat(paymentSubmissions() - submissionsBefore).isEqualTo(1);
		assertThat(providerPayments(transfer.reference())).isEqualTo(1);
	}

	/**
	 * A transfer flagged for reconciliation (as after a 409 reference conflict, which also
	 * clears its retry time) must not be moved on automatically, even by a redelivered message.
	 */
	@Test
	void flaggedPendingTransferIsNotProcessedByRedeliveredMessage() throws Exception {
		TransferResponse transfer = accept("Invoice SIM:PENDING");
		awaitEvent(transfer.reference(), "PAYMENT_ACCEPTED");
		Transfer stored = transfer(transfer.reference());
		this.jdbc.sql("UPDATE transfers SET reconciliation_required = TRUE, next_attempt_at = NULL WHERE id = :id")
			.param("id", stored.getId())
			.update();
		resolvePayment(transfer.reference(), "SUCCEEDED", false);

		this.rabbit.send(MessagingTopology.EXCHANGE, MessagingTopology.PROCESS_ROUTING_KEY,
				MessageBuilder.withBody(("{\"transferId\":\"" + stored.getId() + "\"}").getBytes())
					.setContentType(MessageProperties.CONTENT_TYPE_JSON)
					.build());

		Thread.sleep(2000);
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.PAYMENT_PENDING);
		assertThat(eventTypes(transfer.reference())).doesNotContain("PAYMENT_CONFIRMED");
	}

	@Test
	void providerRefusalIsADefiniteFailure() {
		TransferResponse transfer = accept("Invoice SIM:NOT_A_REAL_TOKEN");

		awaitState(transfer.reference(), TransferState.REFUNDED);
		assertThat(transfer(transfer.reference()).getFailureReason()).isEqualTo("UNKNOWN_SCENARIO");
		assertThat(providerPayments(transfer.reference())).isZero();
	}

}
