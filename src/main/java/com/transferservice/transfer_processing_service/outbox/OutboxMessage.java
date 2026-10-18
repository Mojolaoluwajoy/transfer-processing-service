package com.transferservice.transfer_processing_service.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A message to publish to RabbitMQ, written in the same transaction as the change it
 * announces so that a commit can never happen without its message (or vice versa).
 */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private UUID aggregateId;

	private String messageType;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> payload;

	private int attemptCount;

	private String lastError;

	private Instant nextAttemptAt;

	private Instant createdAt;

	private Instant publishedAt;

	protected OutboxMessage() {
	}

	public static OutboxMessage of(UUID aggregateId, String messageType, Map<String, Object> payload) {
		OutboxMessage message = new OutboxMessage();
		message.aggregateId = aggregateId;
		message.messageType = messageType;
		message.payload = payload;
		message.createdAt = Instant.now();
		message.nextAttemptAt = message.createdAt;
		return message;
	}

	public Long getId() {
		return this.id;
	}

	public UUID getAggregateId() {
		return this.aggregateId;
	}

	public String getMessageType() {
		return this.messageType;
	}

	public Map<String, Object> getPayload() {
		return this.payload;
	}

	public int getAttemptCount() {
		return this.attemptCount;
	}

	public String getLastError() {
		return this.lastError;
	}

	public Instant getNextAttemptAt() {
		return this.nextAttemptAt;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getPublishedAt() {
		return this.publishedAt;
	}

}
