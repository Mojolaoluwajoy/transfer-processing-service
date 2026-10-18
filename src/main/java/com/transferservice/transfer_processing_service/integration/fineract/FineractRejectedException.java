package com.transferservice.transfer_processing_service.integration.fineract;

/**
 * Fineract definitively refused the request (4xx) without recording an operation, e.g. an
 * unknown account. No money moved.
 */
public class FineractRejectedException extends RuntimeException {

	private final int status;

	private final String code;

	public FineractRejectedException(int status, String code) {
		super("Fineract rejected the request: " + status + " " + code);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	/** The operation reference is already bound to a different request: needs a human. */
	public boolean isReferenceConflict() {
		return this.status == 409;
	}

}
