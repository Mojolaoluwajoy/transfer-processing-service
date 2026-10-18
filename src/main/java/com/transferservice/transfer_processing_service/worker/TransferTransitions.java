package com.transferservice.transfer_processing_service.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.transferservice.transfer_processing_service.integration.fineract.FineractOperation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperationRepository;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEvent;
import com.transferservice.transfer_processing_service.transfer.TransferEventRepository;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state change a worker makes, each in its own transaction with its audit event.
 * <p>
 * Fencing: each method first checks that the caller still holds the lease and that the
 * transfer is in the expected state. The entity's {@code @Version} then guarantees that if
 * another worker claimed the lease after that check, this transaction fails at commit
 * instead of overwriting the newer owner's work.
 */
@Component
class TransferTransitions {

	private final TransferRepository transfers;

	private final FinancialOperationRepository operations;

	private final TransferEventRepository events;

	private final RetryPolicy retryPolicy;

	TransferTransitions(TransferRepository transfers, FinancialOperationRepository operations,
			TransferEventRepository events, RetryPolicy retryPolicy) {
		this.transfers = transfers;
		this.operations = operations;
		this.events = events;
		this.retryPolicy = retryPolicy;
	}

	/** Records the intent to debit before Fineract is called. */
	@Transactional
	public FinancialOperation beginDebit(UUID transferId, String owner) {
		Transfer transfer = leased(transferId, owner, TransferState.RECEIVED);
		FinancialOperation debit = this.operations.save(FinancialOperation.pending(transfer, FinancialOperation.Type.DEBIT));
		transfer.moveTo(TransferState.DEBIT_PENDING);
		transfer.scheduleNextAttempt(this.retryPolicy.afterLease());
		audit(transfer, "DEBIT_REQUESTED", TransferState.RECEIVED, owner,
				Map.of("operationReference", debit.getOperationReference(), "amount", debit.getAmount().toPlainString()));
		return debit;
	}

	@Transactional
	public void recordDebitOutcome(UUID transferId, String owner, FineractOperation result, String source) {
		Transfer transfer = leased(transferId, owner, TransferState.DEBIT_PENDING);
		FinancialOperation debit = debitOf(transfer);
		Map<String, Object> detail = new HashMap<>();
		detail.put("operationReference", debit.getOperationReference());
		detail.put("source", source);
		if (result.committed()) {
			debit.markSucceeded(result.transactionId());
			transfer.moveTo(TransferState.DEBITED);
			transfer.clearNextAttempt();
			detail.put("transactionId", result.transactionId());
			audit(transfer, "DEBIT_CONFIRMED", TransferState.DEBIT_PENDING, owner, detail);
		}
		else {
			rejectDebit(transfer, debit, result.rejectionReason(), owner, detail);
		}
	}

	/** Fineract refused without recording an operation (e.g. unknown account): no money moved. */
	@Transactional
	public void recordDebitRefused(UUID transferId, String owner, String reason) {
		Transfer transfer = leased(transferId, owner, TransferState.DEBIT_PENDING);
		FinancialOperation debit = debitOf(transfer);
		Map<String, Object> detail = new HashMap<>();
		detail.put("operationReference", debit.getOperationReference());
		rejectDebit(transfer, debit, reason, owner, detail);
	}

	@Transactional
	public void recordDebitUnknown(UUID transferId, String owner, String error) {
		Transfer transfer = leased(transferId, owner, TransferState.DEBIT_PENDING);
		FinancialOperation debit = debitOf(transfer);
		debit.markUnknown(error);
		transfer.recordUnresolvedAttempt(this.retryPolicy.nextAttempt(transfer.getAttemptCount()));
		audit(transfer, "DEBIT_OUTCOME_UNKNOWN", TransferState.DEBIT_PENDING, owner,
				Map.of("operationReference", debit.getOperationReference(), "error", String.valueOf(error)));
	}

	/** Something only a human can resolve; stop automatic processing but keep the state. */
	@Transactional
	public void flagForReconciliation(UUID transferId, String owner, TransferState expected, String reason) {
		Transfer transfer = leased(transferId, owner, expected);
		transfer.flagForReconciliation();
		transfer.clearNextAttempt();
		audit(transfer, "RECONCILIATION_REQUIRED", expected, owner, Map.of("reason", reason));
	}

	private void rejectDebit(Transfer transfer, FinancialOperation debit, String reason, String owner,
			Map<String, Object> detail) {
		debit.markFailed(reason);
		transfer.fail(TransferState.DEBIT_FAILED, reason);
		detail.put("reason", String.valueOf(reason));
		audit(transfer, "DEBIT_REJECTED", TransferState.DEBIT_PENDING, owner, detail);
	}

	private Transfer leased(UUID transferId, String owner, TransferState expected) {
		Transfer transfer = this.transfers.findById(transferId).orElseThrow();
		if (!owner.equals(transfer.getLeaseOwner())) {
			throw new LeaseLostException(transferId, "lease held by " + transfer.getLeaseOwner());
		}
		if (transfer.getState() != expected) {
			throw new LeaseLostException(transferId, "expected " + expected + " but was " + transfer.getState());
		}
		return transfer;
	}

	private FinancialOperation debitOf(Transfer transfer) {
		return this.operations.findByTransferIdAndOperationType(transfer.getId(), FinancialOperation.Type.DEBIT)
			.orElseThrow();
	}

	private void audit(Transfer transfer, String eventType, TransferState from, String actor,
			Map<String, Object> detail) {
		this.events.save(TransferEvent.of(transfer.getId(), eventType, from, transfer.getState(), actor, detail));
	}

}
