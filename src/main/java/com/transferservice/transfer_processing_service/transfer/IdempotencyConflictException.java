package com.transferservice.transfer_processing_service.transfer;

public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException(String externalReference) {
		super("External reference " + externalReference + " was already used for a different request");
	}

}
