package com.transferservice.transfer_processing_service.worker;

import java.util.UUID;

/**
 * Named points in processing where a test can simulate a worker crash. The production
 * implementation does nothing.
 */
public interface ProcessingCheckpoints {

	enum Checkpoint {

		/** Fineract answered the debit, but the worker has not saved the answer yet. */
		AFTER_DEBIT_CALL,

		/** The provider answered the submission, but the worker has not saved the answer yet. */
		AFTER_PAYMENT_CALL

	}

	void reached(Checkpoint checkpoint, UUID transferId);

}
