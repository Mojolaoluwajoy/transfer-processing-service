package com.transferservice.transfer_processing_service.worker;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.transferservice.transfer_processing_service.integration.fineract.FineractClient;
import com.transferservice.transfer_processing_service.integration.fineract.FineractOperation;
import com.transferservice.transfer_processing_service.integration.fineract.FineractOutcomeUnknownException;
import com.transferservice.transfer_processing_service.integration.fineract.FineractRejectedException;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperationRepository;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.worker.ProcessingCheckpoints.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Drives one transfer as far as it can go. Both RabbitMQ messages and the recovery poller
 * call {@link #process}; neither carries state of its own, so duplicates and redeliveries are
 * harmless: the database state decides the next step, and the lease ensures only one worker
 * acts on a transfer at a time.
 * <p>
 * External calls never happen inside a database transaction. The intent is committed
 * first, then the call is made, then the outcome is committed. A crash between those steps
 * leaves a pending operation that is always resolved by lookup before anything is resent.
 */
@Component
class TransferProcessor {

	static final List<String> NON_TERMINAL_STATES = Arrays.stream(TransferState.values())
		.filter((state) -> !state.isTerminal())
		.map(Enum::name)
		.toList();

	private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

	private final String instanceId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

	private final TransferRepository transfers;

	private final FinancialOperationRepository operations;

	private final TransferTransitions transitions;

	private final FineractClient fineract;

	private final ProcessingCheckpoints checkpoints;

	private final RetryPolicy retryPolicy;

	TransferProcessor(TransferRepository transfers, FinancialOperationRepository operations,
			TransferTransitions transitions, FineractClient fineract, ProcessingCheckpoints checkpoints,
			RetryPolicy retryPolicy) {
		this.transfers = transfers;
		this.operations = operations;
		this.transitions = transitions;
		this.fineract = fineract;
		this.checkpoints = checkpoints;
		this.retryPolicy = retryPolicy;
	}

	public void process(UUID transferId) {
		// A fresh owner token per attempt, so even two threads of one instance are fenced apart.
		String owner = this.instanceId + "/" + UUID.randomUUID().toString().substring(0, 8);
		if (this.transfers.claimLease(transferId, owner, this.retryPolicy.leaseSeconds(), NON_TERMINAL_STATES) == 0) {
			log.debug("Transfer {} is terminal or leased by another worker", transferId);
			return;
		}
		try {
			while (advance(transferId, owner)) {
				// keep going until the transfer waits on something or is finished
			}
			this.transfers.releaseLease(transferId, owner);
		}
		catch (LeaseLostException | ObjectOptimisticLockingFailureException ex) {
			// Someone else owns the transfer now; leave their lease alone.
			log.warn("Stopped processing transfer {}: {}", transferId, ex.getMessage());
		}
		// Any other exception propagates without releasing the lease, exactly like a crash:
		// the lease expires and recovery resumes from the last committed state.
	}

	/** Performs the next step. Returns true if there may be another step to take now. */
	private boolean advance(UUID transferId, String owner) {
		Transfer transfer = this.transfers.findById(transferId).orElseThrow();
		return switch (transfer.getState()) {
			case RECEIVED -> {
				FinancialOperation debit = this.transitions.beginDebit(transferId, owner);
				yield debit(transfer, debit, owner, false);
			}
			case DEBIT_PENDING -> debit(transfer, debitOf(transferId), owner, true);
			default -> false;
		};
	}

	/**
	 * @param resumed true when an earlier attempt may already have reached Fineract, in which
	 * case the outcome is looked up before anything is sent again
	 */
	private boolean debit(Transfer transfer, FinancialOperation debit, String owner, boolean resumed) {
		UUID transferId = transfer.getId();
		try {
			if (resumed) {
				Optional<FineractOperation> existing = this.fineract.lookup(debit.getOperationReference());
				if (existing.isPresent()) {
					this.transitions.recordDebitOutcome(transferId, owner, existing.get(), "lookup");
					return true;
				}
			}
			FineractOperation result = this.fineract.debit(debit.getOperationReference(), transfer.getSourceAccount(),
					transfer.getAmount());
			this.checkpoints.reached(Checkpoint.AFTER_DEBIT_CALL, transferId);
			this.transitions.recordDebitOutcome(transferId, owner, result, "response");
			return true;
		}
		catch (FineractOutcomeUnknownException ex) {
			this.transitions.recordDebitUnknown(transferId, owner, ex.getMessage());
			return false;
		}
		catch (FineractRejectedException ex) {
			if (ex.isReferenceConflict()) {
				this.transitions.flagForReconciliation(transferId, owner, TransferState.DEBIT_PENDING,
						"Fineract reports a conflicting operation for " + debit.getOperationReference());
			}
			else {
				this.transitions.recordDebitRefused(transferId, owner, ex.code());
			}
			return false;
		}
	}

	private FinancialOperation debitOf(UUID transferId) {
		return this.operations.findByTransferIdAndOperationType(transferId, FinancialOperation.Type.DEBIT)
			.orElseThrow();
	}

}
