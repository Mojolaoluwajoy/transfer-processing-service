package com.transferservice.transfer_processing_service.worker;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.transferservice.transfer_processing_service.callback.CallbackOutcome;
import com.transferservice.transfer_processing_service.callback.ProviderCallback;
import com.transferservice.transfer_processing_service.callback.ProviderCallbackRepository;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Provider callbacks delivered by the mock provider to the real callback endpoint.
 */
class CallbackProcessingTest extends WorkerTestSupport {

	@Autowired
	ProviderCallbackRepository callbacks;

	/** Mandatory scenario 6: a callback arrives before the provider submission response is saved. */
	@Test
	void callbackArrivingBeforeSubmissionResponseSettlesPaymentOnce() {
		TransferResponse transfer = accept("Invoice SIM:EARLY_CALLBACK");

		awaitState(transfer.reference(), TransferState.COMPLETED);
		// The callback settled the payment; the worker's late response then changed nothing.
		assertThat(eventTypes(transfer.reference())).containsOnlyOnce("PAYMENT_CONFIRMED");
		assertThat(event(transfer.reference(), "PAYMENT_CONFIRMED").getDetail()).containsEntry("source", "callback");
		assertThat(callbacksFor(transfer.reference())).extracting(ProviderCallback::getOutcome)
			.containsExactly(CallbackOutcome.APPLIED);
		assertThat(operation(transfer.reference(), FinancialOperation.Type.PAYMENT).getStatus())
			.isEqualTo(FinancialOperation.Status.SUCCEEDED);
		assertThat(providerPayments(transfer.reference())).isEqualTo(1);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
	}

	/** Mandatory scenario 7 (callback half): duplicate callbacks create no additional effects. */
	@Test
	void duplicateCallbacksCreateNoAdditionalEffects() {
		TransferResponse transfer = accept("Invoice SIM:DUPLICATE_CALLBACKS");

		awaitState(transfer.reference(), TransferState.COMPLETED);
		// Three deliveries: one event twice (redelivery) and a second event with the same outcome.
		await().atMost(Duration.ofSeconds(10)).until(() -> deliveredMockCallbacks(transfer.reference()) == 3);
		List<ProviderCallback> stored = callbacksFor(transfer.reference());
		assertThat(stored).hasSize(2);
		assertThat(stored).extracting(ProviderCallback::getOutcome)
			.doesNotContain(CallbackOutcome.CONFLICT)
			.allMatch((o) -> o == CallbackOutcome.APPLIED || o == CallbackOutcome.CONSISTENT);
		assertThat(eventTypes(transfer.reference())).containsOnlyOnce("PAYMENT_CONFIRMED");
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(providerPayments(transfer.reference())).isEqualTo(1);
		assertThat(this.operations.findByTransferIdAndOperationType(transfer(transfer.reference()).getId(),
				FinancialOperation.Type.REFUND)).isEmpty();
	}

	@Test
	void delayedCallbackAfterSettlementIsConsistent() {
		TransferResponse transfer = accept("Invoice SIM:DELAYED_CALLBACK");

		awaitState(transfer.reference(), TransferState.COMPLETED);
		await().atMost(Duration.ofSeconds(10)).until(() -> !callbacksFor(transfer.reference()).isEmpty());
		assertThat(callbacksFor(transfer.reference())).extracting(ProviderCallback::getOutcome)
			.containsExactly(CallbackOutcome.CONSISTENT);
		assertThat(transfer(transfer.reference()).isReconciliationRequired()).isFalse();
	}

	@Test
	void conflictingFailureAfterSuccessIsKeptAndFlaggedNotApplied() {
		TransferResponse transfer = accept("Invoice SIM:CONFLICTING_CALLBACKS");

		awaitState(transfer.reference(), TransferState.COMPLETED);
		await().atMost(Duration.ofSeconds(10)).until(() -> transfer(transfer.reference()).isReconciliationRequired());

		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.COMPLETED);
		assertThat(eventTypes(transfer.reference())).contains("CALLBACK_CONFLICT");
		assertThat(callbacksFor(transfer.reference())).extracting(ProviderCallback::getReportedStatus)
			.contains("SUCCEEDED", "FAILED");
		assertThat(callbacksFor(transfer.reference())).extracting(ProviderCallback::getOutcome)
			.contains(CallbackOutcome.CONFLICT);
		assertThat(operation(transfer.reference(), FinancialOperation.Type.PAYMENT).getStatus())
			.isEqualTo(FinancialOperation.Status.SUCCEEDED);
	}

	@Test
	void failureCallbackSettlesPendingPayment() {
		TransferResponse transfer = accept("Invoice SIM:PENDING");
		awaitEvent(transfer.reference(), "PAYMENT_ACCEPTED");

		resolvePayment(transfer.reference(), "FAILED", true);

		awaitAnyState(transfer.reference(), TransferState.PAYMENT_FAILED, TransferState.REFUND_PENDING,
				TransferState.REFUNDED);
		await().atMost(Duration.ofSeconds(10)).until(() -> !callbacksFor(transfer.reference()).isEmpty());
		assertThat(callbacksFor(transfer.reference())).extracting(ProviderCallback::getOutcome)
			.containsAnyOf(CallbackOutcome.APPLIED, CallbackOutcome.CONSISTENT);
	}

	@Test
	void callbackWithoutProviderPaymentIdKeepsTheRecordedOne() {
		TransferResponse transfer = accept("Invoice SIM:PENDING");
		awaitEvent(transfer.reference(), "PAYMENT_ACCEPTED");
		String recordedId = operation(transfer.reference(), FinancialOperation.Type.PAYMENT).getExternalId();
		assertThat(recordedId).isNotBlank();

		String body = "{\"eventId\":\"EVT-" + UUID.randomUUID() + "\",\"paymentReference\":\"PAY-"
				+ transfer.reference() + "\",\"status\":\"SUCCEEDED\"}";
		assertThat(postCallback(body, sign(body)).getBody()).containsEntry("outcome", "APPLIED");

		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.COMPLETED);
		assertThat(operation(transfer.reference(), FinancialOperation.Type.PAYMENT).getExternalId())
			.isEqualTo(recordedId);
	}

	@Test
	void repeatedEventIdIsReportedAsDuplicate() {
		String body = callbackBody("EVT-" + UUID.randomUUID(), "PAY-UNKNOWN-" + UUID.randomUUID(), "SUCCEEDED");

		assertThat(postCallback(body, sign(body)).getBody()).containsEntry("outcome", "UNMATCHED");
		assertThat(postCallback(body, sign(body)).getBody()).containsEntry("outcome", "DUPLICATE");
	}

	@Test
	void badSignatureIsRejectedAndNotStored() {
		String eventId = "EVT-" + UUID.randomUUID();
		String body = callbackBody(eventId, "PAY-X", "SUCCEEDED");

		assertThatThrownBy(() -> postCallback(body, sign(body + "tampered"))).isInstanceOfSatisfying(
				HttpClientErrorException.class,
				(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
		assertThatThrownBy(() -> postCallback(body, null)).isInstanceOfSatisfying(HttpClientErrorException.class,
				(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
		assertThat(this.callbacks.findByProviderEventId(eventId)).isEmpty();
	}

	private List<ProviderCallback> callbacksFor(String reference) {
		return this.callbacks.findByPaymentReferenceOrderByIdAsc("PAY-" + reference);
	}

	private long deliveredMockCallbacks(String reference) {
		return this.jdbc.sql("""
				SELECT count(*) FROM mock_provider.callbacks
				WHERE payment_reference = :ref AND delivered_at IS NOT NULL
				""").param("ref", "PAY-" + reference).query(Long.class).single();
	}

}
