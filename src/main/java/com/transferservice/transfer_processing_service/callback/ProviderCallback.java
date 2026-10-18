package com.transferservice.transfer_processing_service.callback;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A provider callback exactly as received, with what applying it did.
 */
@Entity
@Table(name = "provider_callbacks")
public class ProviderCallback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String providerEventId;

	private String paymentReference;

	private String reportedStatus;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> payload;

	@Enumerated(EnumType.STRING)
	private CallbackOutcome outcome;

	private Instant receivedAt;

	private Instant processedAt;

	protected ProviderCallback() {
	}

	void markProcessed(CallbackOutcome outcome) {
		this.outcome = outcome;
		this.processedAt = Instant.now();
	}

	public Long getId() {
		return this.id;
	}

	public String getProviderEventId() {
		return this.providerEventId;
	}

	public String getPaymentReference() {
		return this.paymentReference;
	}

	public String getReportedStatus() {
		return this.reportedStatus;
	}

	public Map<String, Object> getPayload() {
		return this.payload;
	}

	public CallbackOutcome getOutcome() {
		return this.outcome;
	}

	public Instant getReceivedAt() {
		return this.receivedAt;
	}

	public Instant getProcessedAt() {
		return this.processedAt;
	}

}
