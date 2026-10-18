package com.transferservice.transfer_processing_service.integration.provider;

/**
 * A payment as reported by the provider on submission or status lookup.
 */
public record ProviderPayment(String paymentReference, String providerPaymentId, String status,
		String failureReason) {

	public boolean succeeded() {
		return "SUCCEEDED".equals(this.status);
	}

	public boolean failed() {
		return "FAILED".equals(this.status);
	}

	/** Accepted but not yet settled: not evidence of success or failure. */
	public boolean processing() {
		return !succeeded() && !failed();
	}

}
