package com.transferservice.transfer_processing_service.callback;

/**
 * What applying a provider callback did.
 */
public enum CallbackOutcome {

	/** Stored, not yet applied (only seen if processing failed part-way). */
	RECEIVED,

	/** Settled a pending payment. */
	APPLIED,

	/** Agreed with the outcome already recorded; nothing changed. */
	CONSISTENT,

	/** Contradicted the recorded outcome; nothing was overwritten and the transfer was flagged. */
	CONFLICT,

	/** Not a final status, or the transfer is in a state where it has no bearing. */
	IGNORED,

	/** No payment of ours has this reference. */
	UNMATCHED,

	/** This event id was already received; not stored again. */
	DUPLICATE

}
