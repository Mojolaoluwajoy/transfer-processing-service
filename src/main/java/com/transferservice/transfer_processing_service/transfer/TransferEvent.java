package com.transferservice.transfer_processing_service.transfer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit record of a state transition or financial operation attempt.
 */
@Entity
@Immutable
@Table(name = "transfer_events")
public class TransferEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private UUID transferId;

	private String eventType;

	@Enumerated(EnumType.STRING)
	private TransferState fromState;

	@Enumerated(EnumType.STRING)
	private TransferState toState;

	private String actor;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> detail;

	private Instant createdAt;

	protected TransferEvent() {
	}

	public static TransferEvent of(UUID transferId, String eventType, TransferState fromState, TransferState toState,
			String actor, Map<String, Object> detail) {
		TransferEvent event = new TransferEvent();
		event.transferId = transferId;
		event.eventType = eventType;
		event.fromState = fromState;
		event.toState = toState;
		event.actor = actor;
		event.detail = detail;
		event.createdAt = Instant.now();
		return event;
	}

	public Long getId() {
		return this.id;
	}

	public UUID getTransferId() {
		return this.transferId;
	}

	public String getEventType() {
		return this.eventType;
	}

	public TransferState getFromState() {
		return this.fromState;
	}

	public TransferState getToState() {
		return this.toState;
	}

	public String getActor() {
		return this.actor;
	}

	public Map<String, Object> getDetail() {
		return this.detail;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
