package com.transferservice.transfer_processing_service.partner;

public class SourceAccountNotPermittedException extends RuntimeException {

	public SourceAccountNotPermittedException(String sourceAccount) {
		super("Partner is not authorised to debit account " + sourceAccount);
	}

}
