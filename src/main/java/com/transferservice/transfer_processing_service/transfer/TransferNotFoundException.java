package com.transferservice.transfer_processing_service.transfer;

public class TransferNotFoundException extends RuntimeException {

	public TransferNotFoundException(String reference) {
		super("No transfer " + reference);
	}

}
