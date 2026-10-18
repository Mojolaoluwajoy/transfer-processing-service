package com.transferservice.transfer_processing_service.integration.fineract;

/**
 * A Fineract operation outcome as returned by debit, refund or lookup.
 */
public record FineractOperation(String operationReference, String operationType, String status,
		String rejectionReason, String transactionId) {

	public boolean committed() {
		return "COMMITTED".equals(this.status);
	}

}
