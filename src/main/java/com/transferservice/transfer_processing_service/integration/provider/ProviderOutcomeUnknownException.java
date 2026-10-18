package com.transferservice.transfer_processing_service.integration.provider;

/**
 * The call ended without a definite answer (timeout, connection failure, 5xx). The provider
 * may or may not have accepted the payment, so it must be resolved by lookup or callback.
 */
public class ProviderOutcomeUnknownException extends RuntimeException {

	public ProviderOutcomeUnknownException(String message, Throwable cause) {
		super(message, cause);
	}

}
