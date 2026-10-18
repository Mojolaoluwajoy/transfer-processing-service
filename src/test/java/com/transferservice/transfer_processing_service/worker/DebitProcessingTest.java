package com.transferservice.transfer_processing_service.worker;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestTransfers;
import com.transferservice.transfer_processing_service.TestcontainersConfiguration;
import com.transferservice.transfer_processing_service.messaging.MessagingTopology;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEvent;
import com.transferservice.transfer_processing_service.transfer.TransferEventRepository;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import com.transferservice.transfer_processing_service.worker.ProcessingCheckpoints.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Debit step of the worker against the mock Fineract, through the real API, outbox and
 * RabbitMQ. Short timeouts and leases keep recovery scenarios fast.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "fineract.read-timeout-ms=500", "worker.lease-seconds=2", "worker.retry.initial-ms=200",
				"worker.retry.max-ms=1000", "worker.recovery.poll-ms=200" })
class DebitProcessingTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransferRepository transfers;

	@Autowired
	TransferEventRepository events;

	@Autowired
	RabbitTemplate rabbit;

	@MockitoBean
	ProcessingCheckpoints checkpoints;

	RestClient client;

	String sourceAccount;

	TestPartner partner;

	@BeforeEach
	void setUp() {
		this.client = RestClient.builder().baseUrl("http://localhost:" + this.port).build();
		this.sourceAccount = TestTransfers.randomAccount();
		this.partner = TestPartners.create(this.jdbc, this.sourceAccount);
		TestPartners.fund(this.jdbc, this.sourceAccount, "100000.00");
	}

	@AfterEach
	void clearFaults() {
		this.client.delete().uri("/mock/fineract/faults").retrieve().toBodilessEntity();
	}

	/** Mandatory scenario 1: twenty simultaneous identical requests, one transfer, one committed debit. */
	@Test
	void twentySimultaneousIdenticalRequestsProduceOneDebit() throws Exception {
		Map<String, Object> request = TestTransfers.request("dup-" + UUID.randomUUID(), this.sourceAccount);
		List<TransferResponse> responses = concurrently(20, () -> TestTransfers.post(this.client, this.partner, request).getBody());

		String reference = responses.get(0).reference();
		assertThat(responses).extracting(TransferResponse::reference).containsOnly(reference);
		awaitState(reference, TransferState.DEBITED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("75000.00");
	}

	/** Mandatory scenario 2: two simultaneous transfers cannot spend more than the available balance. */
	@Test
	void twoSimultaneousTransfersCannotOverspend() throws Exception {
		TestPartners.fund(this.jdbc, this.sourceAccount, "30000.00");

		List<TransferResponse> responses = concurrently(2, () -> TestTransfers.post(this.client, this.partner,
				TestTransfers.request("spend-" + UUID.randomUUID(), this.sourceAccount)).getBody());

		List<Transfer> settled = responses.stream()
			.map((r) -> awaitAnyState(r.reference(), TransferState.DEBITED, TransferState.DEBIT_FAILED))
			.toList();
		assertThat(settled).extracting(Transfer::getState)
			.containsExactlyInAnyOrder(TransferState.DEBITED, TransferState.DEBIT_FAILED);
		assertThat(settled).filteredOn((t) -> t.getState() == TransferState.DEBIT_FAILED)
			.singleElement()
			.satisfies((t) -> assertThat(t.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS"));
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(balance(this.sourceAccount)).isEqualByComparingTo("5000.00");
	}

	/** Mandatory scenario 3: the debit commits but the response times out; recovery confirms it by lookup. */
	@Test
	void debitCommittedBeforeTimeoutIsConfirmedWithoutSecondDebit() {
		this.client.post().uri("/mock/fineract/faults").body(Map.of("count", 1, "delayMs", 1500)).retrieve()
			.toBodilessEntity();
		long debitCallsBefore = debitRequests();

		TransferResponse transfer = accept();

		awaitState(transfer.reference(), TransferState.DEBITED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		// One debit call (which timed out); the outcome was then learned by lookup, not by resending.
		assertThat(debitRequests() - debitCallsBefore).isEqualTo(1);
		assertThat(eventTypes(transfer.reference())).containsSubsequence("DEBIT_REQUESTED", "DEBIT_OUTCOME_UNKNOWN",
				"DEBIT_CONFIRMED");
		assertThat(confirmation(transfer.reference()).getDetail()).containsEntry("source", "lookup");
	}

	/** Mandatory scenario 4: the worker dies after the debit, before saving it; a replacement recovers. */
	@Test
	void workerCrashAfterDebitIsRecoveredByReplacementWorker() {
		doThrow(new IllegalStateException("simulated worker crash")).doNothing()
			.when(this.checkpoints)
			.reached(eq(Checkpoint.AFTER_DEBIT_CALL), any());
		long debitCallsBefore = debitRequests();

		TransferResponse transfer = accept();

		awaitState(transfer.reference(), TransferState.DEBITED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(debitRequests() - debitCallsBefore).isEqualTo(1);
		TransferEvent requested = event(transfer.reference(), "DEBIT_REQUESTED");
		TransferEvent confirmed = confirmation(transfer.reference());
		assertThat(confirmed.getDetail()).containsEntry("source", "lookup");
		// Confirmed by a different processing attempt than the one that crashed.
		assertThat(confirmed.getActor()).isNotEqualTo(requested.getActor());
	}

	/** Mandatory scenario 7 (redelivery half): repeated messages create no additional debits. */
	@Test
	void redeliveredMessagesCreateNoAdditionalDebits() throws Exception {
		TransferResponse transfer = accept();
		awaitState(transfer.reference(), TransferState.DEBITED);
		long debitCallsBefore = debitRequests();
		Transfer stored = transfer(transfer.reference());

		for (int i = 0; i < 5; i++) {
			this.rabbit.send(MessagingTopology.EXCHANGE, MessagingTopology.PROCESS_ROUTING_KEY,
					MessageBuilder.withBody(("{\"transferId\":\"" + stored.getId() + "\"}").getBytes())
						.setContentType(MessageProperties.CONTENT_TYPE_JSON)
						.build());
		}

		Thread.sleep(1500);
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.DEBITED);
		assertThat(committedDebits(this.sourceAccount)).isEqualTo(1);
		assertThat(debitRequests()).isEqualTo(debitCallsBefore);
	}

	@Test
	void unknownSourceAccountInFineractFailsWithoutMovingMoney() {
		String unfunded = TestTransfers.randomAccount();
		TestPartner other = TestPartners.create(this.jdbc, unfunded);

		TransferResponse transfer = TestTransfers.post(this.client, other,
				TestTransfers.request("unfunded-" + UUID.randomUUID(), unfunded)).getBody();

		await().atMost(Duration.ofSeconds(15)).until(() -> this.transfers
			.findByPartnerIdAndReference(other.id(), transfer.reference()).orElseThrow()
			.getState() == TransferState.DEBIT_FAILED);
		assertThat(this.transfers.findByPartnerIdAndReference(other.id(), transfer.reference()).orElseThrow()
			.getFailureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
	}

	private TransferResponse accept() {
		return TestTransfers.post(this.client, this.partner,
				TestTransfers.request("debit-" + UUID.randomUUID(), this.sourceAccount)).getBody();
	}

	private Transfer transfer(String reference) {
		return this.transfers.findByPartnerIdAndReference(this.partner.id(), reference).orElseThrow();
	}

	private void awaitState(String reference, TransferState state) {
		await().atMost(Duration.ofSeconds(15)).until(() -> transfer(reference).getState() == state);
	}

	private Transfer awaitAnyState(String reference, TransferState... states) {
		await().atMost(Duration.ofSeconds(15)).until(() -> List.of(states).contains(transfer(reference).getState()));
		return transfer(reference);
	}

	private List<String> eventTypes(String reference) {
		return this.events.findByTransferIdOrderByIdAsc(transfer(reference).getId())
			.stream()
			.map(TransferEvent::getEventType)
			.toList();
	}

	private TransferEvent event(String reference, String type) {
		return this.events.findByTransferIdOrderByIdAsc(transfer(reference).getId())
			.stream()
			.filter((e) -> e.getEventType().equals(type))
			.findFirst()
			.orElseThrow();
	}

	private TransferEvent confirmation(String reference) {
		return event(reference, "DEBIT_CONFIRMED");
	}

	private long committedDebits(String account) {
		return this.jdbc.sql("""
				SELECT count(*) FROM mock_fineract.operations
				WHERE account_number = :account AND operation_type = 'DEBIT' AND status = 'COMMITTED'
				""").param("account", account).query(Long.class).single();
	}

	private BigDecimal balance(String account) {
		return this.jdbc.sql("SELECT available_balance FROM mock_fineract.accounts WHERE account_number = :account")
			.param("account", account)
			.query(BigDecimal.class)
			.single();
	}

	private long debitRequests() {
		Map<?, ?> stats = this.client.get().uri("/mock/fineract/stats").retrieve().body(Map.class);
		return ((Number) stats.get("debitRequests")).longValue();
	}

	private static <T> List<T> concurrently(int count, java.util.concurrent.Callable<T> call) throws Exception {
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
