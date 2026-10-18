package com.transferservice.transfer_processing_service.integration.provider;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
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
 * HTTP client for the payment provider. Every failure is classified as either a definite
 * rejection ({@link ProviderRejectedException}) or an unknown outcome
 * ({@link ProviderOutcomeUnknownException}).
 */
@Component
public class ProviderClient {

	private final Environment environment;

	private final RestClient restClient;

	ProviderClient(Environment environment, @Value("${provider.connect-timeout-ms}") long connectTimeoutMs,
			@Value("${provider.read-timeout-ms}") long readTimeoutMs) {
		this.environment = environment;
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
		factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	public ProviderPayment submit(String paymentReference, String destinationAccount, String destinationBankCode,
			BigDecimal amount, String currency, String narration) {
		Map<String, Object> body = new HashMap<>();
		body.put("paymentReference", paymentReference);
		body.put("destinationAccount", destinationAccount);
		body.put("destinationBankCode", destinationBankCode);
		body.put("amount", amount);
		body.put("currency", currency);
		body.put("narration", narration);
		try {
			return this.restClient.post().uri(baseUrl() + "/payments").body(body).retrieve().body(ProviderPayment.class);
		}
		catch (HttpClientErrorException ex) {
			throw new ProviderRejectedException(ex.getStatusCode().value(), problemCode(ex));
		}
		catch (RestClientException ex) {
			// Timeouts, connection failures and 5xx: the provider may have accepted the payment.
			throw new ProviderOutcomeUnknownException("Provider call failed: " + ex.getMessage(), ex);
		}
	}

	/** Empty means the provider has no payment with this reference: it was never accepted. */
	public Optional<ProviderPayment> lookup(String paymentReference) {
		try {
			return Optional.ofNullable(this.restClient.get()
				.uri(baseUrl() + "/payments/{ref}", paymentReference)
				.retrieve()
				.body(ProviderPayment.class));
		}
		catch (HttpClientErrorException ex) {
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			throw new ProviderOutcomeUnknownException("Lookup failed: " + ex.getStatusCode(), ex);
		}
		catch (RestClientException ex) {
			throw new ProviderOutcomeUnknownException("Lookup failed: " + ex.getMessage(), ex);
		}
	}

	private String baseUrl() {
		// Resolved per call: in tests and local runs the mock's port is only known after startup.
		return this.environment.getRequiredProperty("provider.base-url");
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
