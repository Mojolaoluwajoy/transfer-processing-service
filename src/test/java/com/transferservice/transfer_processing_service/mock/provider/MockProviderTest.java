package com.transferservice.transfer_processing_service.mock.provider;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.transferservice.transfer_processing_service.TestcontainersConfiguration;
import com.transferservice.transfer_processing_service.common.Hashing;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.PaymentResponse;
import com.transferservice.transfer_processing_service.mock.provider.MockProviderApi.StatsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Import({ TestcontainersConfiguration.class, MockProviderTest.CallbackReceiver.class,
		MockProviderTest.CallbackReceiverSecurity.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"mock.provider.callback-url=http://localhost:${local.server.port}/test/provider-callbacks",
		"mock.provider.callback-delay-ms=100", "mock.provider.delayed-callback-ms=1500",
		"mock.provider.slow-response-ms=1500" })
class MockProviderTest {

	static final String SECRET = "dev-callback-secret";

	@LocalServerPort
	int port;

	@Autowired
	JsonMapper jsonMapper;

	RestClient client;

	String ref;

	@BeforeEach
	void setUp() {
		this.client = client(Duration.ofSeconds(10));
		this.ref = "PAY-" + UUID.randomUUID();
	}

	@Test
	void successfulPaymentSendsOneSignedCallback() {
		PaymentResponse response = submit(this.client, "Invoice");

		assertThat(response.status()).isEqualTo("SUCCEEDED");
		assertThat(response.replayed()).isFalse();
		await().until(() -> callbacks().size() == 1);
		Map<String, Object> callback = callbacks().get(0);
		assertThat(callback).containsEntry("status", "SUCCEEDED")
			.containsEntry("providerPaymentId", response.providerPaymentId());
		assertThat(lookup().status()).isEqualTo("SUCCEEDED");
	}

	@Test
	void identicalResubmissionIsReplayedAndConflictingReuseRejected() {
		StatsResponse before = stats();
		PaymentResponse first = submit(this.client, "Invoice");
		PaymentResponse second = submit(this.client, "Invoice");

		assertThat(second.replayed()).isTrue();
		assertThat(second.providerPaymentId()).isEqualTo(first.providerPaymentId());
		StatsResponse after = stats();
		assertThat(after.submissionRequests() - before.submissionRequests()).isEqualTo(2);
		assertThat(after.acceptedPayments() - before.acceptedPayments()).isEqualTo(1);
		assertThatThrownBy(() -> submit(this.client, "Different narration")).isInstanceOfSatisfying(
				HttpClientErrorException.class, (ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void failedPaymentReportsFailure() {
		PaymentResponse response = submit(this.client, "Invoice SIM:FAIL");

		assertThat(response.status()).isEqualTo("FAILED");
		assertThat(response.failureReason()).isEqualTo("SIMULATED_FAILURE");
		await().until(() -> callbacks().size() == 1);
		assertThat(callbacks().get(0)).containsEntry("status", "FAILED");
	}

	@Test
	void slowResponseAcceptsPaymentBeforeClientTimesOut() {
		RestClient impatient = client(Duration.ofMillis(300));

		assertThatThrownBy(() -> submit(impatient, "Invoice SIM:SLOW_RESPONSE"))
			.isInstanceOf(ResourceAccessException.class);

		assertThat(lookup().status()).isEqualTo("SUCCEEDED");
		PaymentResponse retry = submit(impatient, "Invoice SIM:SLOW_RESPONSE");
		assertThat(retry.replayed()).isTrue();
	}

	@Test
	void duplicateCallbacksRepeatEventAndOutcome() {
		submit(this.client, "Invoice SIM:DUPLICATE_CALLBACKS");

		await().until(() -> callbacks().size() == 3);
		List<Map<String, Object>> received = callbacks();
		assertThat(received).extracting((c) -> c.get("status")).containsOnly("SUCCEEDED");
		// Two deliveries share an event id (redelivery); the third is a new event with the same outcome.
		assertThat(received.stream().map((c) -> c.get("eventId")).distinct().count()).isEqualTo(2);
	}

	@Test
	void conflictingCallbacksAnnounceOppositeOutcomeLater() {
		submit(this.client, "Invoice SIM:FAIL SIM:CONFLICTING_CALLBACKS");

		await().until(() -> callbacks().size() == 2);
		assertThat(callbacks()).extracting((c) -> c.get("status")).containsExactly("FAILED", "SUCCEEDED");
		assertThat(lookup().status()).isEqualTo("SUCCEEDED");
	}

	@Test
	void earlyCallbackArrivesBeforeSubmissionResponse() {
		submit(this.client, "Invoice SIM:EARLY_CALLBACK");

		// No waiting: the callback was delivered before the submission call returned.
		assertThat(callbacks()).hasSize(1);
	}

	@Test
	void delayedCallbackArrivesLate() throws InterruptedException {
		submit(this.client, "Invoice SIM:DELAYED_CALLBACK");

		Thread.sleep(700);
		assertThat(callbacks()).isEmpty();
		await().until(() -> callbacks().size() == 1);
	}

	@Test
	void pendingPaymentStaysUnresolvedUntilResolved() throws InterruptedException {
		PaymentResponse response = submit(this.client, "Invoice SIM:PENDING");
		assertThat(response.status()).isEqualTo("PROCESSING");
		Thread.sleep(500);
		assertThat(callbacks()).isEmpty();

		resolve("FAILED");

		await().until(() -> callbacks().size() == 1);
		assertThat(callbacks().get(0)).containsEntry("status", "FAILED");
		assertThat(lookup().status()).isEqualTo("FAILED");
		assertThatThrownBy(() -> resolve("SUCCEEDED")).isInstanceOfSatisfying(HttpClientErrorException.class,
				(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void unknownSimulationTokenIsRejected() {
		assertThatThrownBy(() -> submit(this.client, "Invoice SIM:EXPLODE")).isInstanceOfSatisfying(
				HttpClientErrorException.class,
				(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
	}

	private PaymentResponse submit(RestClient restClient, String narration) {
		return restClient.post().uri("/mock/provider/payments")
			.body(Map.of("paymentReference", this.ref, "destinationAccount", "0123456789", "destinationBankCode",
					"000004", "amount", new BigDecimal("25000.00"), "currency", "NGN", "narration", narration))
			.retrieve().body(PaymentResponse.class);
	}

	private PaymentResponse resolve(String status) {
		return this.client.post().uri("/mock/provider/payments/{ref}/resolve", this.ref)
			.body(Map.of("status", status)).retrieve().body(PaymentResponse.class);
	}

	private PaymentResponse lookup() {
		return this.client.get().uri("/mock/provider/payments/{ref}", this.ref).retrieve().body(PaymentResponse.class);
	}

	private StatsResponse stats() {
		return this.client.get().uri("/mock/provider/stats").retrieve().body(StatsResponse.class);
	}

	/** Callbacks received for this test's payment, in arrival order, after signature checks. */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> callbacks() {
		return CallbackReceiver.received.stream().map((body) -> (Map<String, Object>) this.jsonMapper.readValue(body, Map.class))
			.filter((c) -> this.ref.equals(c.get("paymentReference")))
			.toList();
	}

	private RestClient client(Duration readTimeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		factory.setReadTimeout(readTimeout);
		return RestClient.builder().baseUrl("http://localhost:" + this.port).requestFactory(factory).build();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CallbackReceiverSecurity {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain testCallbackReceiverChain(HttpSecurity http) {
			return http.securityMatcher("/test/**")
				.csrf((csrf) -> csrf.disable())
				.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
				.build();
		}

	}

	@RestController
	static class CallbackReceiver {

		static final Queue<byte[]> received = new ConcurrentLinkedQueue<>();

		@PostMapping("/test/provider-callbacks")
		void receive(@RequestBody byte[] body, @RequestHeader(MockProviderCallbackSender.SIGNATURE_HEADER) String signature) {
			assertThat(signature).isEqualTo(Hashing.hmacSha256Hex(SECRET, body));
			received.add(body);
		}

	}

}
