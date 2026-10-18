package com.transferservice.transfer_processing_service.mock.fineract;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request and response types of the mock Fineract HTTP API.
 */
public final class MockFineractApi {

	private MockFineractApi() {
	}

	public record DebitRequest(
			@NotBlank @Size(max = 60) String operationReference,
			@NotBlank @Size(max = 20) String accountNumber,
			@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
	}

	public record RefundRequest(
			@NotBlank @Size(max = 60) String operationReference,
			@NotBlank @Size(max = 60) String originalReference,
			@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
	}

	public record OperationResponse(String operationReference, String operationType, String status,
			String rejectionReason, String accountNumber, BigDecimal amount, String originalReference,
			String transactionId, OffsetDateTime createdAt, boolean replayed) {
	}

	public record AccountRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal balance) {
	}

	public record AccountResponse(String accountNumber, String currency, BigDecimal availableBalance) {
	}

	/** Makes the next {@code count} debit/refund calls commit, then stall for {@code delayMs} before replying. */
	public record FaultRequest(@Min(1) @Max(100) int count, @Min(1) @Max(60_000) long delayMs) {
	}

	/**
	 * Request counts are API calls received (in memory, reset on restart); committed counts are
	 * financial effects (from the database). Tests compare the two.
	 */
	public record StatsResponse(long debitRequests, long refundRequests, long committedDebits, long rejectedDebits,
			long committedRefunds) {
	}

}
