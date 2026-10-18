package com.transferservice.transfer_processing_service.mock.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response types of the mock payment provider HTTP API.
 */
public final class MockProviderApi {

	private MockProviderApi() {
	}

	public record PaymentRequest(
			@NotBlank @Size(max = 60) String paymentReference,
			@NotBlank @Size(max = 20) String destinationAccount,
			@NotBlank @Size(max = 10) String destinationBankCode,
			@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
			@NotBlank @Size(min = 3, max = 3) String currency,
			@Size(max = 255) String narration) {
	}

	public record PaymentResponse(String paymentReference, String providerPaymentId, String status,
			String failureReason, BigDecimal amount, String currency, OffsetDateTime createdAt, boolean replayed) {
	}

	/** Test control: settles a {@code SIM:PENDING} payment. */
	public record ResolveRequest(@NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status, Boolean sendCallback) {
	}

	/**
	 * Submission requests are API calls received (in memory, reset on restart); the rest are
	 * durable outcomes from the database. Tests compare the two.
	 */
	public record StatsResponse(long submissionRequests, long acceptedPayments, long succeededPayments,
			long failedPayments, long processingPayments, long callbacksDelivered) {
	}

}
