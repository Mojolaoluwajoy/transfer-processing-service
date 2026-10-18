package com.transferservice.transfer_processing_service.worker;

import java.time.Duration;
import java.util.UUID;

import com.transferservice.transfer_processing_service.callback.CallbackOutcome;
import com.transferservice.transfer_processing_service.callback.ProviderCallback;
import com.transferservice.transfer_processing_service.callback.ProviderCallbackRepository;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import com.transferservice.transfer_processing_service.worker.ProcessingCheckpoints.Checkpoint;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * Refunds of confirmed payment failures, against the mock Fineract and provider.
 */
class RefundProcessingTest extends WorkerTestSupport {

	@Autowired
	TransferProcessor processor;

	@Autowired
	ProviderCallbackRepository callbacks;

	@Test
	void confirmedFailureIsRefundedToSourceAccount() {
		TransferResponse transfer = accept("Invoice SIM:FAIL");

		awaitState(transfer.reference(), TransferState.REFUNDED);
		FinancialOperation refund = operation(transfer.reference(), FinancialOperation.Type.REFUND);
		assertThat(refund.getStatus()).isEqualTo(FinancialOperation.Status.SUCCEEDED);
		assertThat(refund.getOperationReference()).isEqualTo("RF-" + transfer.reference());
		assertThat(eventTypes(transfer.reference())).containsSubsequence("DEBIT_CONFIRMED", "PAYMENT_FAILED",
				"REFUND_REQUESTED", "REFUND_CONFIRMED");
		assertThat(committedRefunds(transfer.reference())).isEqualTo(1);
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("100000.00");
	}

	/**
	 * Mandatory scenario 9: a confirmed failure produces one refund despite concurrent
	 * recovery workers. The first worker dies right after Fineract commits the refund; then
	 * eight workers race to recover it (alongside the recovery poller).
	 */
	@Test
	void confirmedFailureProducesOneRefundDespiteConcurrentRecoveryWorkers() throws Exception {
		doThrow(new IllegalStateException("simulated worker crash")).doNothing()
			.when(this.checkpoints)
			.reached(eq(Checkpoint.AFTER_REFUND_CALL), any());
		long refundCallsBefore = refundRequests();

		TransferResponse transfer = accept("Invoice SIM:FAIL");
		awaitState(transfer.reference(), TransferState.REFUND_PENDING);
		UUID transferId = transfer(transfer.reference()).getId();

		await().atMost(Duration.ofSeconds(20)).until(() -> {
			concurrently(8, () -> {
				this.processor.process(transferId);
				return null;
			});
			return transfer(transfer.reference()).getState() == TransferState.REFUNDED;
		});

		assertThat(committedRefunds(transfer.reference())).isEqualTo(1);
		// The refund was sent once; every recovery learned its outcome by lookup.
		assertThat(refundRequests() - refundCallsBefore).isEqualTo(1);
		assertThat(event(transfer.reference(), "REFUND_CONFIRMED").getDetail()).containsEntry("source", "lookup");
		assertThat(eventTypes(transfer.reference())).containsOnlyOnce("REFUND_REQUESTED", "REFUND_CONFIRMED");
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("100000.00");
	}

	/**
	 * Mandatory scenario 10: a success callback arriving after a completed refund preserves
	 * both facts and flags the conflict for reconciliation.
	 */
	@Test
	void successCallbackAfterCompletedRefundPreservesBothFactsAndFlagsConflict() {
		// FAILED is announced first; the contradicting SUCCEEDED arrives later.
		TransferResponse transfer = accept("Invoice SIM:FAIL SIM:CONFLICTING_CALLBACKS");
		awaitState(transfer.reference(), TransferState.REFUNDED);

		await().atMost(Duration.ofSeconds(10)).until(() -> transfer(transfer.reference()).isReconciliationRequired());

		// Fact 1: the refund stands.
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.REFUNDED);
		assertThat(operation(transfer.reference(), FinancialOperation.Type.REFUND).getStatus())
			.isEqualTo(FinancialOperation.Status.SUCCEEDED);
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("100000.00");
		// Fact 2: the provider's success claim is kept, not discarded.
		assertThat(this.callbacks.findByPaymentReferenceOrderByIdAsc("PAY-" + transfer.reference()))
			.filteredOn((c) -> c.getReportedStatus().equals("SUCCEEDED"))
			.singleElement()
			.extracting(ProviderCallback::getOutcome)
			.isEqualTo(CallbackOutcome.CONFLICT);
		assertThat(event(transfer.reference(), "CALLBACK_CONFLICT").getDetail())
			.containsEntry("reportedStatus", "SUCCEEDED")
			.containsEntry("recordedState", "REFUNDED");
	}

	/** A success claim that arrives before the refund starts holds the refund for reconciliation. */
	@Test
	void successCallbackBeforeRefundStartsHoldsTheRefund() throws Exception {
		// The worker dies after submitting; while its lease runs out, the provider's FAILED
		// callback moves the transfer to PAYMENT_FAILED, then a SUCCEEDED claim arrives.
		doThrow(new IllegalStateException("simulated worker crash")).doNothing()
			.when(this.checkpoints)
			.reached(eq(Checkpoint.AFTER_PAYMENT_CALL), any());
		TransferResponse transfer = accept("Invoice SIM:FAIL");
		awaitState(transfer.reference(), TransferState.PAYMENT_FAILED);

		String body = callbackBody("EVT-" + UUID.randomUUID(), "PAY-" + transfer.reference(), "SUCCEEDED");
		assertThat(postCallback(body, sign(body)).getBody()).containsEntry("outcome", "CONFLICT");

		// Well past the crashed worker's lease: recovery must still leave it alone.
		Thread.sleep(3000);
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.PAYMENT_FAILED);
		assertThat(transfer(transfer.reference()).isReconciliationRequired()).isTrue();
		assertThat(this.operations.findByTransferIdAndOperationType(transfer(transfer.reference()).getId(),
				FinancialOperation.Type.REFUND)).isEmpty();
		assertThat(committedRefunds(transfer.reference())).isZero();
	}

	private long committedRefunds(String reference) {
		return this.jdbc.sql("""
				SELECT count(*) FROM mock_fineract.operations
				WHERE original_reference = :debitRef AND operation_type = 'REFUND' AND status = 'COMMITTED'
				""").param("debitRef", "DR-" + reference).query(Long.class).single();
	}

}
