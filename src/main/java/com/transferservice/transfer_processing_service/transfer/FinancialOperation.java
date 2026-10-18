package com.transferservice.transfer_processing_service.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One external financial effect (debit, payment or refund) of a transfer. The operation
 * reference is deterministic and is what the external system deduplicates on, so every
 * retry and every recovery lookup targets the same operation.
 */
@Entity
@Table(name = "financial_operations")
public class FinancialOperation {

	public enum Type {

		DEBIT("DR-"), PAYMENT("PAY-"), REFUND("RF-");

		private final String referencePrefix;

		Type(String referencePrefix) {
			this.referencePrefix = referencePrefix;
		}

		public String referenceFor(Transfer transfer) {
			return this.referencePrefix + transfer.getReference();
		}

	}

	public enum Status {

		/** Recorded before the first call; the external system may not have seen it yet. */
		PENDING,

		/** At least one call ended without a definite answer; resolve by lookup before resending. */
		UNKNOWN,

		SUCCEEDED,

		FAILED

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private UUID transferId;

	@Enumerated(EnumType.STRING)
	private Type operationType;

	private String operationReference;

	@Column(precision = 19, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	private Status status;

	private String externalId;

	private int attemptCount;

	private String lastError;

	@Version
	private Long version;

	private Instant createdAt;

	private Instant updatedAt;

	protected FinancialOperation() {
	}

	public static FinancialOperation pending(Transfer transfer, Type type) {
		FinancialOperation operation = new FinancialOperation();
		operation.transferId = transfer.getId();
		operation.operationType = type;
		operation.operationReference = type.referenceFor(transfer);
		operation.amount = transfer.getAmount();
		operation.status = Status.PENDING;
		operation.createdAt = Instant.now();
		operation.updatedAt = operation.createdAt;
		return operation;
	}

	public void markUnknown(String error) {
		this.attemptCount++;
		this.status = Status.UNKNOWN;
		this.lastError = truncate(error);
	}

	public void markSucceeded(String externalId) {
		this.attemptCount++;
		this.status = Status.SUCCEEDED;
		this.externalId = externalId;
		this.lastError = null;
	}

	public void markFailed(String reason) {
		this.attemptCount++;
		this.status = Status.FAILED;
		this.lastError = truncate(reason);
	}

	@PreUpdate
	void touch() {
		this.updatedAt = Instant.now();
	}

	private static String truncate(String text) {
		return (text != null && text.length() > 1000) ? text.substring(0, 1000) : text;
	}

	public Long getId() {
		return this.id;
	}

	public UUID getTransferId() {
		return this.transferId;
	}

	public Type getOperationType() {
		return this.operationType;
	}

	public String getOperationReference() {
		return this.operationReference;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public Status getStatus() {
		return this.status;
	}

	public String getExternalId() {
		return this.externalId;
	}

	public int getAttemptCount() {
		return this.attemptCount;
	}

	public String getLastError() {
		return this.lastError;
	}

}
