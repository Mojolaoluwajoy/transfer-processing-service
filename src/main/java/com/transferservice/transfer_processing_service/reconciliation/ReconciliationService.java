package com.transferservice.transfer_processing_service.reconciliation;

import java.util.List;
import java.util.Set;

import com.transferservice.transfer_processing_service.callback.ProviderCallbackRepository;
import com.transferservice.transfer_processing_service.reconciliation.ReconciliationItem.Callback;
import com.transferservice.transfer_processing_service.reconciliation.ReconciliationItem.Issue;
import com.transferservice.transfer_processing_service.reconciliation.ReconciliationItem.Operation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperationRepository;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEventRepository;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view of transfers flagged for reconciliation. Resolving them (deciding which
 * fact is true and correcting the ledger) is a manual process outside this service.
 */
@Service
public class ReconciliationService {

	/** Audit events that explain a reconciliation flag. */
	static final Set<String> ISSUE_EVENTS = Set.of("CALLBACK_CONFLICT", "RECONCILIATION_REQUIRED", "REFUND_REJECTED");

	private final TransferRepository transfers;

	private final TransferEventRepository events;

	private final FinancialOperationRepository operations;

	private final ProviderCallbackRepository callbacks;

	ReconciliationService(TransferRepository transfers, TransferEventRepository events,
			FinancialOperationRepository operations, ProviderCallbackRepository callbacks) {
		this.transfers = transfers;
		this.events = events;
		this.operations = operations;
		this.callbacks = callbacks;
	}

	@Transactional(readOnly = true)
	public List<ReconciliationItem> flagged() {
		return this.transfers.findTop100ByReconciliationRequiredTrueOrderByUpdatedAtAsc()
			.stream()
			.map(this::toItem)
			.toList();
	}

	private ReconciliationItem toItem(Transfer transfer) {
		List<Issue> issues = this.events.findByTransferIdOrderByIdAsc(transfer.getId())
			.stream()
			.filter((event) -> ISSUE_EVENTS.contains(event.getEventType()))
			.map((event) -> new Issue(event.getEventType(), event.getActor(), event.getDetail(), event.getCreatedAt()))
			.toList();
		List<Operation> operations = this.operations.findByTransferIdOrderByIdAsc(transfer.getId())
			.stream()
			.map((op) -> new Operation(op.getOperationType().name(), op.getOperationReference(), op.getStatus().name(),
					op.getExternalId(), op.getAttemptCount(), op.getLastError()))
			.toList();
		List<Callback> callbacks = this.callbacks
			.findByPaymentReferenceOrderByIdAsc(FinancialOperation.Type.PAYMENT.referenceFor(transfer))
			.stream()
			.map((cb) -> new Callback(cb.getProviderEventId(), cb.getReportedStatus(), cb.getOutcome().name(),
					cb.getReceivedAt()))
			.toList();
		return new ReconciliationItem(transfer.getReference(), transfer.getPartnerId(), transfer.getExternalReference(),
				transfer.getState().name(), transfer.getAmount().toPlainString(), transfer.getCurrency(),
				transfer.getFailureReason(), transfer.getUpdatedAt(), issues, operations, callbacks);
	}

}
