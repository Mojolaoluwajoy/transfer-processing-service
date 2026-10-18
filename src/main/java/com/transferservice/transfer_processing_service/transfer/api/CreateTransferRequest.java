package com.transferservice.transfer_processing_service.transfer.api;

import java.math.BigDecimal;

import com.transferservice.transfer_processing_service.transfer.NewTransfer;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v2/transfers}. Amounts are parsed straight into
 * {@link BigDecimal} (from a JSON string or number) and never pass through floating point.
 */
public record CreateTransferRequest(
		@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+",
				message = "may contain only letters, digits and . _ : -") String externalReference,
		@NotBlank @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number") String sourceAccount,
		@NotBlank @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number") String destinationAccount,
		@NotBlank @Pattern(regexp = "\\d{3,6}", message = "must be a 3 to 6 digit bank code") String destinationBankCode,
		@NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@NotBlank @Pattern(regexp = "NGN", message = "only NGN is supported") String currency,
		@Size(max = 255) String narration) {

	NewTransfer toNewTransfer() {
		return new NewTransfer(this.externalReference, this.sourceAccount, this.destinationAccount,
				this.destinationBankCode, this.amount, this.currency, this.narration);
	}

}
