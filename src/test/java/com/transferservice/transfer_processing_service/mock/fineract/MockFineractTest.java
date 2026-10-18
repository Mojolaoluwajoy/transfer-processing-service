package com.transferservice.transfer_processing_service.mock.fineract;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.transferservice.transfer_processing_service.TestcontainersConfiguration;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.AccountResponse;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.OperationResponse;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.StatsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockFineractTest {

	@LocalServerPort
	int port;

	RestClient client;

	String account;

	@BeforeEach
	void setUp() {
		this.client = client(Duration.ofSeconds(10));
		this.account = "ACC" + UUID.randomUUID().toString().substring(0, 8);
		setBalance(this.account, "100.00");
	}

	@AfterEach
	void clearFaults() {
		this.client.delete().uri("/mock/fineract/faults").retrieve().toBodilessEntity();
	}

	@Test
	void debitCommitsAndReducesBalance() {
		OperationResponse response = debit(this.client, "DR-1-" + this.account, "40.00");

		assertThat(response.status()).isEqualTo("COMMITTED");
		assertThat(response.replayed()).isFalse();
		assertThat(response.transactionId()).isNotBlank();
		assertThat(balance()).isEqualByComparingTo("60.00");
	}

	@Test
	void identicalRepeatReturnsStoredResultWithoutSecondEffect() {
		StatsResponse before = stats();
		String ref = "DR-2-" + this.account;

		OperationResponse first = debit(this.client, ref, "40.00");
		OperationResponse second = debit(this.client, ref, "40");

		assertThat(second.replayed()).isTrue();
		assertThat(second.transactionId()).isEqualTo(first.transactionId());
		assertThat(balance()).isEqualByComparingTo("60.00");
		StatsResponse after = stats();
		assertThat(after.debitRequests() - before.debitRequests()).isEqualTo(2);
		assertThat(after.committedDebits() - before.committedDebits()).isEqualTo(1);
	}

	@Test
	void reusingReferenceForDifferentRequestIsRejected() {
		String ref = "DR-3-" + this.account;
		debit(this.client, ref, "40.00");

		assertThatThrownBy(() -> debit(this.client, ref, "41.00")).isInstanceOfSatisfying(
				HttpClientErrorException.class, (ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		assertThat(balance()).isEqualByComparingTo("60.00");
	}

	@Test
	void insufficientFundsIsRecordedAsRejection() {
		String ref = "DR-4-" + this.account;

		OperationResponse response = debit(this.client, ref, "100.01");
		OperationResponse repeat = debit(this.client, ref, "100.01");

		assertThat(response.status()).isEqualTo("REJECTED");
		assertThat(response.rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(repeat.status()).isEqualTo("REJECTED");
		assertThat(balance()).isEqualByComparingTo("100.00");
	}

	@Test
	void concurrentDebitsNeverOverspend() throws Exception {
		// 20 debits of 10.00 against a balance of 100.00: exactly 10 may commit.
		List<OperationResponse> results = concurrently(20, (i) -> debit(this.client, "DR-5-" + i + "-" + this.account, "10.00"));

		assertThat(results).filteredOn((r) -> r.status().equals("COMMITTED")).hasSize(10);
		assertThat(results).filteredOn((r) -> r.status().equals("REJECTED")).hasSize(10);
		assertThat(balance()).isEqualByComparingTo("0.00");
	}

	@Test
	void concurrentIdenticalDebitsCommitOnce() throws Exception {
		StatsResponse before = stats();
		String ref = "DR-6-" + this.account;

		List<OperationResponse> results = concurrently(20, (i) -> debit(this.client, ref, "10.00"));

		assertThat(results).extracting(OperationResponse::transactionId).containsOnly(results.get(0).transactionId());
		assertThat(results).filteredOn((r) -> !r.replayed()).hasSize(1);
		assertThat(balance()).isEqualByComparingTo("90.00");
		assertThat(stats().committedDebits() - before.committedDebits()).isEqualTo(1);
	}

	@Test
	void debitCommitsEvenWhenResponseTimesOut() {
		String ref = "DR-7-" + this.account;
		this.client.post().uri("/mock/fineract/faults").body(Map.of("count", 1, "delayMs", 1500)).retrieve().toBodilessEntity();
		RestClient impatient = client(Duration.ofMillis(300));

		assertThatThrownBy(() -> debit(impatient, ref, "40.00")).isInstanceOf(ResourceAccessException.class);

		OperationResponse lookedUp = this.client.get().uri("/mock/fineract/operations/{ref}", ref).retrieve()
			.body(OperationResponse.class);
		assertThat(lookedUp.status()).isEqualTo("COMMITTED");
		assertThat(balance()).isEqualByComparingTo("60.00");
		assertThat(debit(this.client, ref, "40.00").replayed()).isTrue();
		assertThat(balance()).isEqualByComparingTo("60.00");
	}

	@Test
	void lookupOfUnknownOperationReturns404() {
		assertThatThrownBy(() -> this.client.get().uri("/mock/fineract/operations/{ref}", "missing-" + this.account)
			.retrieve().body(OperationResponse.class)).isInstanceOfSatisfying(HttpClientErrorException.class,
					(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void refundIsIdempotentAndAllowedOncePerDebit() {
		StatsResponse before = stats();
		String debitRef = "DR-8-" + this.account;
		debit(this.client, debitRef, "40.00");

		OperationResponse refund = refund("RF-8-" + this.account, debitRef, "40.00");
		OperationResponse repeat = refund("RF-8-" + this.account, debitRef, "40.00");

		assertThat(refund.status()).isEqualTo("COMMITTED");
		assertThat(repeat.replayed()).isTrue();
		assertThat(balance()).isEqualByComparingTo("100.00");
		assertThatThrownBy(() -> refund("RF-8b-" + this.account, debitRef, "40.00")).isInstanceOfSatisfying(
				HttpClientErrorException.class, (ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		assertThat(balance()).isEqualByComparingTo("100.00");
		assertThat(stats().committedRefunds() - before.committedRefunds()).isEqualTo(1);
	}

	@Test
	void concurrentRefundsOfSameDebitCreditOnce() throws Exception {
		String debitRef = "DR-9-" + this.account;
		debit(this.client, debitRef, "40.00");

		concurrently(10, (i) -> {
			try {
				return refund("RF-9-" + i + "-" + this.account, debitRef, "40.00");
			}
			catch (HttpClientErrorException ex) {
				return null;
			}
		});

		assertThat(balance()).isEqualByComparingTo("100.00");
	}

	private OperationResponse debit(RestClient restClient, String ref, String amount) {
		return restClient.post().uri("/mock/fineract/debits")
			.body(Map.of("operationReference", ref, "accountNumber", this.account, "amount", new BigDecimal(amount)))
			.retrieve().body(OperationResponse.class);
	}

	private OperationResponse refund(String ref, String originalRef, String amount) {
		return this.client.post().uri("/mock/fineract/refunds")
			.body(Map.of("operationReference", ref, "originalReference", originalRef, "amount", new BigDecimal(amount)))
			.retrieve().body(OperationResponse.class);
	}

	private void setBalance(String accountNumber, String balance) {
		this.client.put().uri("/mock/fineract/accounts/{account}", accountNumber)
			.body(Map.of("balance", new BigDecimal(balance))).retrieve().toBodilessEntity();
	}

	private BigDecimal balance() {
		return this.client.get().uri("/mock/fineract/accounts/{account}", this.account).retrieve()
			.body(AccountResponse.class).availableBalance();
	}

	private StatsResponse stats() {
		return this.client.get().uri("/mock/fineract/stats").retrieve().body(StatsResponse.class);
	}

	private RestClient client(Duration readTimeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		factory.setReadTimeout(readTimeout);
		return RestClient.builder().baseUrl("http://localhost:" + this.port).requestFactory(factory).build();
	}

	private static <T> List<T> concurrently(int count, IndexedCall<T> call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					start.await();
					return call.apply(index);
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	interface IndexedCall<T> {

		T apply(int index) throws Exception;

	}

}
