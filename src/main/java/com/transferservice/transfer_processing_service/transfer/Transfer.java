package com.transferservice.transfer_processing_service.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transfers")
public class Transfer {

	@Id
	private UUID id;

	private String reference;

	private UUID partnerId;

	private String externalReference;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 64)
	private String requestHash;

	private String sourceAccount;

	private String destinationAccount;

	private String destinationBankCode;

	@Column(precision = 19, scale = 2)
	private BigDecimal amount;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	private String narration;

	@Enumerated(EnumType.STRING)
	private TransferState state;

	private String failureReason;

	private boolean reconciliationRequired;

	/** Null until first persisted, which also tells Spring Data the entity is new. */
	@Version
	private Long version;

	private String leaseOwner;

	private Instant leaseExpiresAt;

	private int attemptCount;

	private Instant nextAttemptAt;

	private Instant createdAt;

	private Instant updatedAt;

	protected Transfer() {
	}

	/**
	 * @param recoverAfter when recovery may pick the transfer up if its RabbitMQ message has
	 * not been processed by then
	 */
	public static Transfer received(UUID partnerId, String externalReference, String requestHash,
			String sourceAccount, String destinationAccount, String destinationBankCode, BigDecimal amount,
			String currency, String narration, Instant now, Instant recoverAfter) {
		Transfer transfer = new Transfer();
		transfer.id = UUID.randomUUID();
		transfer.reference = "TRF" + transfer.id.toString().replace("-", "").substring(0, 20).toUpperCase();
		transfer.partnerId = partnerId;
		transfer.externalReference = externalReference;
		transfer.requestHash = requestHash;
		transfer.sourceAccount = sourceAccount;
		transfer.destinationAccount = destinationAccount;
		transfer.destinationBankCode = destinationBankCode;
		transfer.amount = amount;
		transfer.currency = currency;
		transfer.narration = narration;
		transfer.state = TransferState.RECEIVED;
		transfer.nextAttemptAt = recoverAfter;
		transfer.createdAt = now;
		transfer.updatedAt = now;
		return transfer;
	}

	/** Moves to a new state; the caller is responsible for checking the transition is legal. */
	public void moveTo(TransferState next) {
		this.state = next;
		if (next.isTerminal()) {
			this.nextAttemptAt = null;
		}
	}

	/** When recovery should next look at this transfer if nothing else moves it on. */
	public void scheduleNextAttempt(Instant at) {
		this.nextAttemptAt = at;
	}

	public void clearNextAttempt() {
		this.nextAttemptAt = null;
	}

	/** Starts a new phase (e.g. payment after debit) with a fresh backoff. */
	public void resetAttempts() {
		this.attemptCount = 0;
	}

	public void recordUnresolvedAttempt(Instant retryAt) {
		this.attemptCount++;
		this.nextAttemptAt = retryAt;
	}

	public void fail(TransferState failedState, String reason) {
		moveTo(failedState);
		this.failureReason = reason;
	}

	public void flagForReconciliation() {
		this.reconciliationRequired = true;
	}

	@PreUpdate
	void touch() {
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return this.id;
	}

	public String getReference() {
		return this.reference;
	}

	public UUID getPartnerId() {
		return this.partnerId;
	}

	public String getExternalReference() {
		return this.externalReference;
	}

	public String getRequestHash() {
		return this.requestHash;
	}

	public String getSourceAccount() {
		return this.sourceAccount;
	}

	public String getDestinationAccount() {
		return this.destinationAccount;
	}

	public String getDestinationBankCode() {
		return this.destinationBankCode;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public String getCurrency() {
		return this.currency;
	}

	public String getNarration() {
		return this.narration;
	}

	public TransferState getState() {
		return this.state;
	}

	public String getFailureReason() {
		return this.failureReason;
	}

	public boolean isReconciliationRequired() {
		return this.reconciliationRequired;
	}

	public Long getVersion() {
		return this.version;
	}

	public String getLeaseOwner() {
		return this.leaseOwner;
	}

	public Instant getLeaseExpiresAt() {
		return this.leaseExpiresAt;
	}

	public int getAttemptCount() {
		return this.attemptCount;
	}

	public Instant getNextAttemptAt() {
		return this.nextAttemptAt;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
