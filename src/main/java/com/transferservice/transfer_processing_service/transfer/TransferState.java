package com.transferservice.transfer_processing_service.transfer;

/**
 * Lifecycle of a transfer. Terminal states never change once reached; contradicting
 * evidence that arrives later is recorded and flags the transfer for reconciliation.
 */
public enum TransferState {

	/** Accepted and durably stored; waiting for a worker. */
	RECEIVED(false),

	/** Debit intent recorded; Fineract may or may not have committed it. Resolved by lookup. */
	DEBIT_PENDING(false),

	/** Fineract rejected the debit (e.g. insufficient funds). No money moved. */
	DEBIT_FAILED(true),

	/** Debit confirmed; payment not yet submitted. */
	DEBITED(false),

	/** Payment intent recorded; the provider may or may not have accepted it. Resolved by lookup or callback. */
	PAYMENT_PENDING(false),

	/** Provider confirmed the payment succeeded. */
	COMPLETED(true),

	/** Provider confirmed the payment failed; a refund is owed. */
	PAYMENT_FAILED(false),

	/** Refund intent recorded; resolved by lookup. */
	REFUND_PENDING(false),

	/** Debit returned to the source account. */
	REFUNDED(true);

	private final boolean terminal;

	TransferState(boolean terminal) {
		this.terminal = terminal;
	}

	public boolean isTerminal() {
		return this.terminal;
	}

}
