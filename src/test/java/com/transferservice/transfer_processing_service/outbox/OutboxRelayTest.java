package com.transferservice.transfer_processing_service.outbox;

import java.time.Duration;
import java.util.UUID;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestTransfers;
import com.transferservice.transfer_processing_service.ToxiproxyTestcontainersConfiguration;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import eu.rekawek.toxiproxy.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Recovery's grace period is set far beyond the test duration, so any transfer that gets
 * processed here was processed because its RabbitMQ message was published and consumed.
 */
@Import(ToxiproxyTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outbox.relay.max-backoff-ms=1000", "worker.recovery.grace-ms=600000" })
class OutboxRelayTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransferRepository transfers;

	@Autowired
	OutboxMessageRepository outbox;

	@Autowired
	Proxy rabbitProxy;

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
	void restoreBroker() throws Exception {
		this.rabbitProxy.enable();
	}

	@Test
	void acceptedTransferIsPublishedAndProcessed() {
		TransferResponse transfer = accept("relay-1");

		OutboxMessage message = awaitPublished(transfer.reference());

		assertThat(message.getAttemptCount()).isZero();
		awaitState(transfer.reference(), TransferState.COMPLETED);
	}

	/** Mandatory scenario 5: RabbitMQ is unavailable after acceptance, and processing resumes when it returns. */
	@Test
	void brokerOutageAfterAcceptanceLosesNothingAndProcessingResumes() throws Exception {
		this.rabbitProxy.disable();

		// Acceptance does not depend on the broker: the transfer and its outbox row commit together.
		TransferResponse transfer = accept("relay-outage");

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			OutboxMessage pending = outboxFor(transfer.reference());
			assertThat(pending.getPublishedAt()).isNull();
			assertThat(pending.getAttemptCount()).isGreaterThanOrEqualTo(2);
			assertThat(pending.getLastError()).isNotBlank();
		});
		assertThat(transfer(transfer.reference()).getState()).isEqualTo(TransferState.RECEIVED);

		this.rabbitProxy.enable();

		OutboxMessage published = awaitPublished(transfer.reference());
		assertThat(published.getLastError()).isNull();
		awaitState(transfer.reference(), TransferState.COMPLETED);
	}

	private TransferResponse accept(String externalReference) {
		ResponseEntity<TransferResponse> response = TestTransfers.post(this.client, this.partner,
				TestTransfers.request(externalReference + "-" + UUID.randomUUID(), this.sourceAccount));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		return response.getBody();
	}

	private Transfer transfer(String reference) {
		return this.transfers.findByPartnerIdAndReference(this.partner.id(), reference).orElseThrow();
	}

	private OutboxMessage outboxFor(String reference) {
		return this.outbox.findByAggregateId(transfer(reference).getId()).getFirst();
	}

	private OutboxMessage awaitPublished(String reference) {
		await().atMost(Duration.ofSeconds(30)).until(() -> outboxFor(reference).getPublishedAt() != null);
		return outboxFor(reference);
	}

	private void awaitState(String reference, TransferState state) {
		await().atMost(Duration.ofSeconds(30)).until(() -> transfer(reference).getState() == state);
	}

}
