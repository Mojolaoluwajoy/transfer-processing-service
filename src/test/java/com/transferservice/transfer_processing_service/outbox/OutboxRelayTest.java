package com.transferservice.transfer_processing_service.outbox;

import java.time.Duration;
import java.util.UUID;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestTransfers;
import com.transferservice.transfer_processing_service.ToxiproxyTestcontainersConfiguration;
import com.transferservice.transfer_processing_service.messaging.MessagingTopology;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import eu.rekawek.toxiproxy.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

@Import(ToxiproxyTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "outbox.relay.max-backoff-ms=1000")
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
	RabbitTemplate rabbit;

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
	}

	@AfterEach
	void restoreBroker() throws Exception {
		this.rabbitProxy.enable();
	}

	@Test
	void acceptedTransferIsPublishedWithConfirm() {
		TransferResponse transfer = accept("relay-1");

		OutboxMessage message = awaitPublished(transfer.reference());

		Message delivered = receiveFor(transfer.reference());
		assertThat(delivered.getMessageProperties().getMessageId()).isEqualTo("outbox-" + message.getId());
		assertThat(delivered.getMessageProperties().getType()).isEqualTo("TRANSFER_ACCEPTED");
		assertThat(message.getAttemptCount()).isZero();
	}

	/** Mandatory scenario 5 (publishing half): RabbitMQ is unavailable after acceptance. */
	@Test
	void brokerOutageAfterAcceptanceLosesNothingAndPublishingResumes() throws Exception {
		this.rabbitProxy.disable();

		// Acceptance does not depend on the broker: the transfer and its outbox row commit together.
		TransferResponse transfer = accept("relay-outage");

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			OutboxMessage pending = outboxFor(transfer.reference());
			assertThat(pending.getPublishedAt()).isNull();
			assertThat(pending.getAttemptCount()).isGreaterThanOrEqualTo(2);
			assertThat(pending.getLastError()).isNotBlank();
		});

		this.rabbitProxy.enable();

		OutboxMessage published = awaitPublished(transfer.reference());
		assertThat(published.getLastError()).isNull();
		assertThat(receiveFor(transfer.reference())).isNotNull();
	}

	private TransferResponse accept(String externalReference) {
		ResponseEntity<TransferResponse> response = TestTransfers.post(this.client, this.partner,
				TestTransfers.request(externalReference + "-" + UUID.randomUUID(), this.sourceAccount));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		return response.getBody();
	}

	private OutboxMessage outboxFor(String reference) {
		UUID transferId = this.transfers.findByPartnerIdAndReference(this.partner.id(), reference).orElseThrow().getId();
		return this.outbox.findByAggregateId(transferId).getFirst();
	}

	private OutboxMessage awaitPublished(String reference) {
		await().atMost(Duration.ofSeconds(30)).until(() -> outboxFor(reference).getPublishedAt() != null);
		return outboxFor(reference);
	}

	/** Drains the process queue until the message for this transfer arrives. */
	private Message receiveFor(String reference) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			Message message = this.rabbit.receive(MessagingTopology.PROCESS_QUEUE, 1000);
			if (message != null && new String(message.getBody()).contains(reference)) {
				return message;
			}
		}
		throw new AssertionError("No message for " + reference + " on " + MessagingTopology.PROCESS_QUEUE);
	}

}
