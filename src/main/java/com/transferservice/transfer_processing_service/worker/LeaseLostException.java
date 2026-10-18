package com.transferservice.transfer_processing_service.worker;

import java.util.UUID;

/**
 * This worker no longer owns the transfer (its lease expired and another worker took over,
 * or the state moved on). It must stop without writing anything.
 */
class LeaseLostException extends RuntimeException {

	LeaseLostException(UUID transferId, String detail) {
		super("Lease lost on transfer " + transferId + ": " + detail);
	}

}
