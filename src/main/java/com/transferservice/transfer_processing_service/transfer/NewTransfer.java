package com.transferservice.transfer_processing_service.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.transferservice.transfer_processing_service.common.Hashing;

/**
 * A validated transfer request. The partner is not part of it: it comes from authentication.
 */
public record NewTransfer(String externalReference, String sourceAccount, String destinationAccount,
		String destinationBankCode, BigDecimal amount, String currency, String narration) {

	public NewTransfer {
		amount = amount.setScale(2, RoundingMode.UNNECESSARY);
	}

	/**
	 * Fingerprint of the request used to detect reuse of an external reference with a
	 * different payload. Fields are joined in a fixed order; "|" cannot appear in any
	 * validated field except the narration, which comes last, so the encoding is unambiguous.
	 */
	public String requestHash() {
		return Hashing.sha256Hex(String.join("|", this.externalReference, this.sourceAccount, this.destinationAccount,
				this.destinationBankCode, this.amount.toPlainString(), this.currency,
				(this.narration != null) ? this.narration : ""));
	}

}
