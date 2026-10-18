package com.transferservice.transfer_processing_service.integration.fineract;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for Fineract. Every failure is classified as either a definite rejection
 * ({@link FineractRejectedException}) or an unknown outcome ({@link FineractOutcomeUnknownException}).
 */
@Component
public class FineractClient {

	private final Environment environment;

	private final RestClient restClient;

	FineractClient(Environment environment, @Value("${fineract.connect-timeout-ms}") long connectTimeoutMs,
			@Value("${fineract.read-timeout-ms}") long readTimeoutMs) {
		this.environment = environment;
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
		factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	public FineractOperation debit(String operationReference, String accountNumber, BigDecimal amount) {
		return post("/debits",
				Map.of("operationReference", operationReference, "accountNumber", accountNumber, "amount", amount));
	}

	public FineractOperation refund(String operationReference, String originalReference, BigDecimal amount) {
		return post("/refunds",
				Map.of("operationReference", operationReference, "originalReference", originalReference, "amount", amount));
	}

	/** Empty means Fineract has no operation with this reference: it was never committed. */
	public Optional<FineractOperation> lookup(String operationReference) {
		try {
			return Optional.ofNullable(this.restClient.get()
				.uri(baseUrl() + "/operations/{ref}", operationReference)
				.retrieve()
				.body(FineractOperation.class));
		}
		catch (HttpClientErrorException ex) {
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			throw new FineractOutcomeUnknownException("Lookup failed: " + ex.getStatusCode(), ex);
		}
		catch (RestClientException ex) {
			throw new FineractOutcomeUnknownException("Lookup failed: " + ex.getMessage(), ex);
		}
	}

	private FineractOperation post(String path, Map<String, Object> body) {
		try {
			return this.restClient.post().uri(baseUrl() + path).body(body).retrieve().body(FineractOperation.class);
		}
		catch (HttpClientErrorException ex) {
			throw new FineractRejectedException(ex.getStatusCode().value(), problemCode(ex));
		}
		catch (RestClientException ex) {
			// Timeouts, connection failures and 5xx: Fineract may have committed.
			throw new FineractOutcomeUnknownException("Fineract call failed: " + ex.getMessage(), ex);
		}
	}

	private String baseUrl() {
		// Resolved per call: in tests and local runs the mock's port is only known after startup.
		return this.environment.getRequiredProperty("fineract.base-url");
	}

	private static String problemCode(HttpClientErrorException ex) {
		try {
			Problem problem = ex.getResponseBodyAs(Problem.class);
			return (problem != null && problem.code() != null) ? problem.code() : ex.getStatusText();
		}
		catch (RuntimeException parseFailure) {
			return ex.getStatusText();
		}
	}

	private record Problem(String code) {
	}

}
