package com.transferservice.transfer_processing_service.worker;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestTransfers;
import com.transferservice.transfer_processing_service.TestcontainersConfiguration;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperationRepository;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEvent;
import com.transferservice.transfer_processing_service.transfer.TransferEventRepository;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.awaitility.Awaitility.await;

/**
 * Shared setup for end-to-end worker tests: the real API, outbox, RabbitMQ and both mocks,
 * with short timeouts and leases so recovery scenarios run quickly. Subclasses share one
 * application context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "fineract.read-timeout-ms=500", "provider.read-timeout-ms=500",
				"mock.provider.slow-response-ms=1500", "worker.lease-seconds=2", "worker.retry.initial-ms=200",
				"worker.retry.max-ms=1000", "worker.recovery.poll-ms=200" })
abstract class WorkerTestSupport {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransferRepository transfers;

	@Autowired
	TransferEventRepository events;

	@Autowired
	FinancialOperationRepository operations;

	@Autowired
	RabbitTemplate rabbit;

	@MockitoBean
	ProcessingCheckpoints checkpoints;

	RestClient client;

	String sourceAccount;

	TestPartner partner;

	@BeforeEach
	void setUpPartner() {
		this.client = RestClient.builder().baseUrl("http://localhost:" + this.port).build();
		this.sourceAccount = TestTransfers.randomAccount();
		this.partner = TestPartners.create(this.jdbc, this.sourceAccount);
		TestPartners.fund(this.jdbc, this.sourceAccount, "100000.00");
	}

	@AfterEach
	void clearFineractFaults() {
		this.client.delete().uri("/mock/fineract/faults").retrieve().toBodilessEntity();
	}

	TransferResponse accept() {
		return accept("Invoice payment");
	}

	TransferResponse accept(String narration) {
		Map<String, Object> request = TestTransfers.request("t-" + UUID.randomUUID(), this.sourceAccount);
		request.put("narration", narration);
		return TestTransfers.post(this.client, this.partner, request).getBody();
	}

	Transfer transfer(String reference) {
		return this.transfers.findByPartnerIdAndReference(this.partner.id(), reference).orElseThrow();
	}

	void awaitState(String reference, TransferState state) {
		await().atMost(Duration.ofSeconds(20)).until(() -> transfer(reference).getState() == state);
	}

	Transfer awaitAnyState(String reference, TransferState... states) {
		await().atMost(Duration.ofSeconds(20)).until(() -> List.of(states).contains(transfer(reference).getState()));
		return transfer(reference);
	}

	void awaitEvent(String reference, String eventType) {
		await().atMost(Duration.ofSeconds(20)).until(() -> eventTypes(reference).contains(eventType));
	}

	List<String> eventTypes(String reference) {
		return this.events.findByTransferIdOrderByIdAsc(transfer(reference).getId())
			.stream()
			.map(TransferEvent::getEventType)
			.toList();
	}

	TransferEvent event(String reference, String type) {
		return this.events.findByTransferIdOrderByIdAsc(transfer(reference).getId())
			.stream()
			.filter((e) -> e.getEventType().equals(type))
			.findFirst()
			.orElseThrow();
	}

	FinancialOperation operation(String reference, FinancialOperation.Type type) {
		return this.operations.findByTransferIdAndOperationType(transfer(reference).getId(), type).orElseThrow();
	}

	long committedDebits(String account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM mock_fineract.operations
				WHERE account_number = :account AND operation_type = 'DEBIT' AND status = 'COMMITTED'
				""").param("account", account).query(Long.class).single();
	}

	BigDecimal balance(String account) {
		return this.jdbc.sql("SELECT available_balance FROM mock_fineract.accounts WHERE account_number = :account")
			.param("account", account)
			.query(BigDecimal.class)
			.single();
	}

	long debitRequests() {
		return stat("/mock/fineract/stats", "debitRequests");
	}

	long paymentSubmissions() {
		return stat("/mock/provider/stats", "submissionRequests");
	}

	long providerPayments(String reference) {
		return this.jdbc.sql("SELECT count(*) FROM mock_provider.payments WHERE payment_reference = :ref")
			.param("ref", "PAY-" + reference)
			.query(Long.class)
			.single();
	}

	void resolvePayment(String reference, String status, boolean sendCallback) {
		this.client.post()
			.uri("/mock/provider/payments/{ref}/resolve", "PAY-" + reference)
			.body(Map.of("status", status, "sendCallback", sendCallback))
			.retrieve()
			.toBodilessEntity();
	}

	private long stat(String path, String field) {
		Map<?, ?> stats = this.client.get().uri(path).retrieve().body(Map.class);
		return ((Number) stats.get(field)).longValue();
	}

	static <T> List<T> concurrently(int count, Callable<T> call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return call.call();
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

}
