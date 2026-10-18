package com.transferservice.transfer_processing_service.mock.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.transferservice.transfer_processing_service.common.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Delivers persisted callbacks to the configured URL with an HMAC signature. Rows are
 * claimed with {@code FOR UPDATE SKIP LOCKED}, so concurrent pollers (or instances) never
 * send the same row twice; a failed delivery is retried with backoff.
 */
@Component
class MockProviderCallbackSender {

	static final String SIGNATURE_HEADER = "X-Provider-Signature";

	private static final Logger log = LoggerFactory.getLogger(MockProviderCallbackSender.class);

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	private final Environment environment;

	private final JsonMapper jsonMapper;

	private final String secret;

	private final int maxAttempts;

	private final RestClient restClient;

	MockProviderCallbackSender(JdbcClient jdbc, TransactionTemplate tx, Environment environment, JsonMapper jsonMapper,
			@Value("${mock.provider.callback-secret}") String secret,
			@Value("${mock.provider.callback-max-attempts}") int maxAttempts) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.environment = environment;
		this.jsonMapper = jsonMapper;
		this.secret = secret;
		this.maxAttempts = maxAttempts;
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
		factory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	@Scheduled(fixedDelayString = "${mock.provider.callback-poll-ms}")
	void deliverDue() {
		claim(null).forEach(this::deliver);
	}

	/** Delivers a payment's due callbacks synchronously (used by {@code SIM:EARLY_CALLBACK}). */
	void deliverNow(String paymentReference) {
		claim(paymentReference).forEach(this::deliver);
	}

	private List<Callback> claim(String paymentReference) {
		// Claiming pushes deliver_at forward, so a sender that crashes mid-delivery is retried later.
		return this.jdbc.sql("""
				UPDATE mock_provider.callbacks c
				SET deliver_at = now() + interval '30 seconds', attempt_count = c.attempt_count + 1
				FROM mock_provider.payments p
				WHERE p.payment_reference = c.payment_reference
				  AND c.id IN (
				      SELECT id FROM mock_provider.callbacks
				      WHERE delivered_at IS NULL AND deliver_at <= now() AND attempt_count < :maxAttempts
				        AND (:ref = '' OR payment_reference = :ref)
				      ORDER BY deliver_at, id
				      LIMIT 20
				      FOR UPDATE SKIP LOCKED)
				RETURNING c.id, c.event_id, c.payment_reference, c.status, c.attempt_count, p.provider_payment_id
				""")
			.param("maxAttempts", this.maxAttempts)
			.param("ref", (paymentReference != null) ? paymentReference : "")
			.query(Callback.class)
			.list()
			.stream()
			.sorted((a, b) -> Long.compare(a.id(), b.id()))
			.toList();
	}

	private void deliver(Callback callback) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("eventId", callback.eventId());
		payload.put("paymentReference", callback.paymentReference());
		payload.put("providerPaymentId", callback.providerPaymentId());
		payload.put("status", callback.status());
		payload.put("failureReason", "FAILED".equals(callback.status()) ? MockProviderService.SIMULATED_FAILURE : null);
		byte[] body = this.jsonMapper.writeValueAsBytes(payload);
		try {
			this.restClient.post()
				.uri(this.environment.getRequiredProperty("mock.provider.callback-url"))
				.contentType(MediaType.APPLICATION_JSON)
				.header(SIGNATURE_HEADER, Hashing.hmacSha256Hex(this.secret, body))
				.body(body)
				.retrieve()
				.toBodilessEntity();
			markDelivered(callback);
		}
		catch (RuntimeException ex) {
			long backoffMs = Math.min(500L << Math.min(callback.attemptCount(), 6), 30_000L);
			log.debug("Callback {} delivery failed (attempt {}): {}", callback.eventId(), callback.attemptCount(),
					ex.getMessage());
			this.jdbc.sql("""
					UPDATE mock_provider.callbacks
					SET deliver_at = now() + :backoffMs * interval '1 millisecond', last_error = :error
					WHERE id = :id
					""").param("backoffMs", backoffMs).param("error", ex.getMessage()).param("id", callback.id()).update();
		}
	}

	private void markDelivered(Callback callback) {
		this.tx.executeWithoutResult((status) -> {
			this.jdbc.sql("UPDATE mock_provider.callbacks SET delivered_at = now() WHERE id = :id")
				.param("id", callback.id())
				.update();
			// The payment reflects the latest outcome the provider has announced, so a
			// conflicting callback also changes what status lookup reports (a reversal).
			this.jdbc.sql("""
					UPDATE mock_provider.payments
					SET status = :status, failure_reason = :reason, updated_at = now()
					WHERE payment_reference = :ref AND status <> :status
					""")
				.param("status", callback.status())
				.param("reason", "FAILED".equals(callback.status()) ? MockProviderService.SIMULATED_FAILURE : null)
				.param("ref", callback.paymentReference())
				.update();
		});
	}

	record Callback(long id, String eventId, String paymentReference, String status, int attemptCount,
			String providerPaymentId) {
	}

}
