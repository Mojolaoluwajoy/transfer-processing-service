package com.transferservice.transfer_processing_service.integration.fineract;

/**
 * The call ended without a definite answer (timeout, connection failure, 5xx). Fineract may
 * or may not have committed the operation, so the caller must resolve it by lookup.
 */
public class FineractOutcomeUnknownException extends RuntimeException {

	public FineractOutcomeUnknownException(String message, Throwable cause) {
		super(message, cause);
	}

}
