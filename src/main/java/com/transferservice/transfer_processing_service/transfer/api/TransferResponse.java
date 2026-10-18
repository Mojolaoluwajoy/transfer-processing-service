package com.transferservice.transfer_processing_service.transfer.api;

import java.time.Instant;

import com.transferservice.transfer_processing_service.transfer.Transfer;

/**
 * Transfer as seen by a partner. The amount is a string so clients never parse it as a float.
 */
public record TransferResponse(String reference, String externalReference, String state, String sourceAccount,
		String destinationAccount, String destinationBankCode, String amount, String currency, String narration,
		String failureReason, boolean reconciliationRequired, Instant createdAt, Instant updatedAt) {

	static TransferResponse from(Transfer transfer) {
		return new TransferResponse(transfer.getReference(), transfer.getExternalReference(),
				transfer.getState().name(), transfer.getSourceAccount(), transfer.getDestinationAccount(),
				transfer.getDestinationBankCode(), transfer.getAmount().toPlainString(), transfer.getCurrency(),
				transfer.getNarration(), transfer.getFailureReason(), transfer.isReconciliationRequired(),
				transfer.getCreatedAt(), transfer.getUpdatedAt());
	}

}
