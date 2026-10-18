package com.transferservice.transfer_processing_service.integration.provider;

/**
 * The provider definitively refused the submission (4xx) without accepting a payment.
 */
public class ProviderRejectedException extends RuntimeException {

	private final int status;

	private final String code;

	public ProviderRejectedException(int status, String code) {
		super("Provider rejected the request: " + status + " " + code);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	/** The payment reference is already bound to a different request: needs a human. */
	public boolean isReferenceConflict() {
		return this.status == 409;
	}

}
