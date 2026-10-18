package com.transferservice.transfer_processing_service.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.transferservice.transfer_processing_service.messaging.MessagingTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox messages to RabbitMQ. A message counts as published only once the broker
 * confirms it and has routed it to a queue; anything else leaves the row unpublished for a
 * later attempt. Delivery is therefore at least once: a crash after the confirm but before
 * the commit republishes the message, which consumers must tolerate.
 */
@Component
class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxMessageRepository outbox;

	private final RabbitTemplate rabbit;

	private final TransactionTemplate tx;

	private final JsonMapper jsonMapper;

	private final int batchSize;

	private final Duration confirmTimeout;

	private final Duration maxBackoff;

	OutboxRelay(OutboxMessageRepository outbox, RabbitTemplate rabbit, TransactionTemplate tx, JsonMapper jsonMapper,
			@Value("${outbox.relay.batch-size}") int batchSize,
			@Value("${outbox.relay.confirm-timeout-ms}") long confirmTimeoutMs,
			@Value("${outbox.relay.max-backoff-ms}") long maxBackoffMs) {
		this.outbox = outbox;
		this.rabbit = rabbit;
		this.tx = tx;
		this.jsonMapper = jsonMapper;
		this.batchSize = batchSize;
		this.confirmTimeout = Duration.ofMillis(confirmTimeoutMs);
		this.maxBackoff = Duration.ofMillis(maxBackoffMs);
	}

	@Scheduled(fixedDelayString = "${outbox.relay.poll-ms}")
	void publishDue() {
		Integer published;
		do {
			published = this.tx.execute((status) -> publishBatch());
		}
		while (published != null && published == this.batchSize);
	}

	/** Returns how many messages were confirmed, so the caller keeps going while there is a backlog. */
	private int publishBatch() {
		List<OutboxMessage> batch = this.outbox.lockDueBatch(this.batchSize);
		if (batch.isEmpty()) {
			return 0;
		}
		List<Sent> sent = new ArrayList<>();
		for (OutboxMessage message : batch) {
			CorrelationData correlation = new CorrelationData("outbox-" + message.getId());
			try {
				this.rabbit.send(MessagingTopology.EXCHANGE, MessagingTopology.PROCESS_ROUTING_KEY, toAmqp(message),
						correlation);
				sent.add(new Sent(message, correlation));
			}
			catch (RuntimeException ex) {
				// Broker unreachable: record the attempt and leave the rest of the batch for the next poll.
				fail(message, "Publish failed: " + NestedExceptionUtils.getMostSpecificCause(ex));
				break;
			}
		}

		long deadline = System.nanoTime() + this.confirmTimeout.toNanos();
		int confirmed = 0;
		for (Sent attempt : sent) {
			String failure = awaitConfirm(attempt.correlation(), deadline);
			if (failure == null) {
				attempt.message().markPublished(Instant.now());
				confirmed++;
			}
			else {
				fail(attempt.message(), failure);
			}
		}
		return confirmed;
	}

	private String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
		try {
			Confirm confirm = correlation.getFuture()
				.get(Math.max(deadlineNanos - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
			if (!confirm.ack()) {
				return "Broker nacked: " + confirm.reason();
			}
			if (correlation.getReturned() != null) {
				return "Unroutable: " + correlation.getReturned().getReplyText();
			}
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return "Interrupted waiting for confirm";
		}
		catch (Exception ex) {
			return "No confirm: " + ex;
		}
	}

	private void fail(OutboxMessage message, String error) {
		Duration backoff = Duration.ofMillis(Math.min(500L << Math.min(message.getAttemptCount(), 10),
				this.maxBackoff.toMillis()));
		message.markFailed(error, Instant.now().plus(backoff));
		log.warn("Outbox message {} not published (attempt {}): {}", message.getId(), message.getAttemptCount(), error);
	}

	private Message toAmqp(OutboxMessage message) {
		return MessageBuilder.withBody(this.jsonMapper.writeValueAsBytes(message.getPayload()))
			.setContentType(MessageProperties.CONTENT_TYPE_JSON)
			.setMessageId("outbox-" + message.getId())
			.setType(message.getMessageType())
			.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
			.build();
	}

	private record Sent(OutboxMessage message, CorrelationData correlation) {
	}

}
