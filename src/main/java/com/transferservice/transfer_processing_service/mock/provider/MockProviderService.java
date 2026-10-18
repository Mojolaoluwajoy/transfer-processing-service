package com.transferservice.transfer_processing_service.mock.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.transferservice.transfer_processing_service.common.Hashing;
import com.transferservice.transfer_processing_service.mock.MockApiException;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.PaymentRequest;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.PaymentResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock payment provider. Payments are keyed by the caller's payment reference; the outcome
 * is decided at submission (or later via resolve for {@code SIM:PENDING}) and announced
 * through persisted callbacks.
 */
@Service
class MockProviderService {

	static final String SIMULATED_FAILURE = "SIMULATED_FAILURE";

	private static final long DUPLICATE_SPACING_MS = 100;

	private final JdbcClient jdbc;

	private final long callbackDelayMs;

	private final long delayedCallbackMs;

	MockProviderService(JdbcClient jdbc, @Value("${mock.provider.callback-delay-ms}") long callbackDelayMs,
			@Value("${mock.provider.delayed-callback-ms}") long delayedCallbackMs) {
		this.jdbc = jdbc;
		this.callbackDelayMs = callbackDelayMs;
		this.delayedCallbackMs = delayedCallbackMs;
	}

	@Transactional
	public Submission submit(PaymentRequest request) {
		BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
		String hash = Hashing.sha256Hex(String.join("|", request.destinationAccount(),
				request.destinationBankCode(), amount.toPlainString(), request.currency(),
				String.valueOf(request.narration())));
		MockProviderScenario scenario = MockProviderScenario.parse(request.narration());
		String status = scenario.initialStatus();

		int inserted = this.jdbc.sql("""
				INSERT INTO mock_provider.payments
				    (payment_reference, provider_payment_id, request_hash, destination_account, destination_bank_code,
				     amount, currency, status, failure_reason, scenario)
				VALUES (:ref, :providerId, :hash, :account, :bank, :amount, :currency, :status, :reason, :scenario)
				ON CONFLICT (payment_reference) DO NOTHING
				""")
			.param("ref", request.paymentReference())
			.param("providerId", "PP" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase())
			.param("hash", hash)
			.param("account", request.destinationAccount())
			.param("bank", request.destinationBankCode())
			.param("amount", amount)
			.param("currency", request.currency())
			.param("status", status)
			.param("reason", "FAILED".equals(status) ? SIMULATED_FAILURE : null)
			.param("scenario", scenario.describe())
			.update();
		if (inserted == 0) {
			Payment existing = findPayment(request.paymentReference()).orElseThrow();
			if (!existing.requestHash().equals(hash)) {
				throw MockApiException.conflict("REFERENCE_CONFLICT",
						"Payment reference " + request.paymentReference() + " was used for a different request");
			}
			return new Submission(toResponse(existing, true), MockProviderScenario.parse(request.narration()));
		}

		scheduleCallbacks(request.paymentReference(), scenario, status);
		return new Submission(toResponse(findPayment(request.paymentReference()).orElseThrow(), false), scenario);
	}

	@Transactional
	public PaymentResponse resolve(String paymentReference, String status, boolean sendCallback) {
		int updated = this.jdbc.sql("""
				UPDATE mock_provider.payments
				SET status = :status, failure_reason = :reason, updated_at = now()
				WHERE payment_reference = :ref AND status = 'PROCESSING'
				""")
			.param("status", status)
			.param("reason", "FAILED".equals(status) ? SIMULATED_FAILURE : null)
			.param("ref", paymentReference)
			.update();
		if (updated == 0) {
			getPayment(paymentReference);
			throw MockApiException.conflict("ALREADY_FINAL", "Payment " + paymentReference + " is not PROCESSING");
		}
		if (sendCallback) {
			insertCallback(newEventId(), paymentReference, status, 0);
		}
		return getPayment(paymentReference);
	}

	@Transactional(readOnly = true)
	public PaymentResponse getPayment(String paymentReference) {
		return findPayment(paymentReference).map((payment) -> toResponse(payment, false))
			.orElseThrow(() -> MockApiException.notFound("PAYMENT_NOT_FOUND", "No payment " + paymentReference));
	}

	@Transactional(readOnly = true)
	public long countPayments(String status) {
		return this.jdbc.sql("SELECT count(*) FROM mock_provider.payments WHERE (:status = 'ANY' OR status = :status)")
			.param("status", status)
			.query(Long.class)
			.single();
	}

	@Transactional(readOnly = true)
	public long countDeliveredCallbacks() {
		return this.jdbc.sql("SELECT count(*) FROM mock_provider.callbacks WHERE delivered_at IS NOT NULL")
			.query(Long.class)
			.single();
	}

	private void scheduleCallbacks(String paymentReference, MockProviderScenario scenario, String status) {
		if ("PROCESSING".equals(status) || scenario.has(MockProviderScenario.NO_CALLBACK)) {
			return;
		}
		long firstAt = scenario.has(MockProviderScenario.EARLY_CALLBACK) ? 0
				: scenario.has(MockProviderScenario.DELAYED_CALLBACK) ? this.delayedCallbackMs : this.callbackDelayMs;
		String eventId = newEventId();
		insertCallback(eventId, paymentReference, status, firstAt);
		if (scenario.has(MockProviderScenario.DUPLICATE_CALLBACKS)) {
			// The same event redelivered, plus a second event announcing the same outcome.
			insertCallback(eventId, paymentReference, status, firstAt + DUPLICATE_SPACING_MS);
			insertCallback(newEventId(), paymentReference, status, firstAt + 2 * DUPLICATE_SPACING_MS);
		}
		if (scenario.has(MockProviderScenario.CONFLICTING_CALLBACKS)) {
			String opposite = "SUCCEEDED".equals(status) ? "FAILED" : "SUCCEEDED";
			insertCallback(newEventId(), paymentReference, opposite, firstAt + this.delayedCallbackMs);
		}
	}

	private void insertCallback(String eventId, String paymentReference, String status, long delayMs) {
		this.jdbc.sql("""
				INSERT INTO mock_provider.callbacks (event_id, payment_reference, status, deliver_at)
				VALUES (:eventId, :ref, :status, now() + :delayMs * interval '1 millisecond')
				""")
			.param("eventId", eventId)
			.param("ref", paymentReference)
			.param("status", status)
			.param("delayMs", delayMs)
			.update();
	}

	private Optional<Payment> findPayment(String paymentReference) {
		return this.jdbc.sql("SELECT * FROM mock_provider.payments WHERE payment_reference = :ref")
			.param("ref", paymentReference)
			.query(Payment.class)
			.optional();
	}

	private static String newEventId() {
		return "EVT-" + UUID.randomUUID();
	}

	private static PaymentResponse toResponse(Payment payment, boolean replayed) {
		return new PaymentResponse(payment.paymentReference(), payment.providerPaymentId(), payment.status(),
				payment.failureReason(), payment.amount(), payment.currency(), payment.createdAt(), replayed);
	}

	record Submission(PaymentResponse response, MockProviderScenario scenario) {
	}

	record Payment(String paymentReference, String providerPaymentId, String requestHash, BigDecimal amount,
			String currency, String status, String failureReason, OffsetDateTime createdAt) {
	}

}
