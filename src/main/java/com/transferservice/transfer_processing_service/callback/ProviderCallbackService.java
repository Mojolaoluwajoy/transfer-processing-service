package com.transferservice.transfer_processing_service.callback;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.transferservice.transfer_processing_service.transfer.FinancialOperation;
import com.transferservice.transfer_processing_service.transfer.FinancialOperationRepository;
import com.transferservice.transfer_processing_service.transfer.Transfer;
import com.transferservice.transfer_processing_service.transfer.TransferEvent;
import com.transferservice.transfer_processing_service.transfer.TransferEventRepository;
import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and applies provider callbacks. A callback can settle a pending payment, but never
 * overwrites an outcome that is already recorded: a contradicting callback is kept, audited
 * and flags the transfer for reconciliation.
 * <p>
 * Callbacks do not take the worker's lease. The transfer row is locked for the duration of
 * this transaction, and the version bump means a worker still holding an older copy (say,
 * about to save a late submission response) fails its write instead of overwriting.
 */
@Service
public class ProviderCallbackService {

	private final ProviderCallbackRepository callbacks;

	private final FinancialOperationRepository operations;

	private final TransferRepository transfers;

	private final TransferEventRepository events;

	ProviderCallbackService(ProviderCallbackRepository callbacks, FinancialOperationRepository operations,
			TransferRepository transfers, TransferEventRepository events) {
		this.callbacks = callbacks;
		this.operations = operations;
		this.transfers = transfers;
		this.events = events;
	}

	@Transactional
	public CallbackOutcome handle(ProviderCallbackPayload callback, String rawBody) {
		if (this.callbacks.insertIfAbsent(callback.eventId(), callback.paymentReference(), callback.status(),
				rawBody) == 0) {
			return CallbackOutcome.DUPLICATE;
		}
		CallbackOutcome outcome = apply(callback);
		this.callbacks.findByProviderEventId(callback.eventId()).orElseThrow().markProcessed(outcome);
		return outcome;
	}

	private CallbackOutcome apply(ProviderCallbackPayload callback) {
		FinancialOperation payment = this.operations.findByOperationReference(callback.paymentReference())
			.filter((op) -> op.getOperationType() == FinancialOperation.Type.PAYMENT)
			.orElse(null);
		if (payment == null) {
			return CallbackOutcome.UNMATCHED;
		}
		boolean succeeded = "SUCCEEDED".equals(callback.status());
		boolean failed = "FAILED".equals(callback.status());
		if (!succeeded && !failed) {
			return CallbackOutcome.IGNORED;
		}

		Transfer transfer = this.transfers.findByIdForUpdate(payment.getTransferId()).orElseThrow();
		String actor = "provider-callback:" + callback.eventId();
		Map<String, Object> detail = new HashMap<>();
		detail.put("eventId", callback.eventId());
		detail.put("operationReference", payment.getOperationReference());
		detail.put("reportedStatus", callback.status());
		detail.put("source", "callback");

		TransferState state = transfer.getState();
		switch (state) {
			case PAYMENT_PENDING -> {
				if (succeeded) {
					payment.markSucceeded(callback.providerPaymentId());
					transfer.moveTo(TransferState.COMPLETED);
					audit(transfer, "PAYMENT_CONFIRMED", state, actor, detail);
				}
				else {
					String reason = (callback.failureReason() != null) ? callback.failureReason() : "PROVIDER_FAILED";
					payment.markFailed(reason);
					transfer.fail(TransferState.PAYMENT_FAILED, reason);
					transfer.resetAttempts();
					// A refund is owed; let recovery start it straight away.
					transfer.scheduleNextAttempt(Instant.now());
					detail.put("reason", reason);
					audit(transfer, "PAYMENT_FAILED", state, actor, detail);
				}
				return CallbackOutcome.APPLIED;
			}
			case COMPLETED -> {
				return succeeded ? CallbackOutcome.CONSISTENT : conflict(transfer, actor, detail);
			}
			case PAYMENT_FAILED, REFUND_PENDING, REFUNDED -> {
				if (failed) {
					return CallbackOutcome.CONSISTENT;
				}
				if (state == TransferState.PAYMENT_FAILED) {
					// The provider now claims success: refunding could pay twice, so hold the
					// refund until someone reconciles. A refund already under way continues.
					transfer.clearNextAttempt();
					detail.put("refundHeld", true);
				}
				return conflict(transfer, actor, detail);
			}
			default -> {
				return CallbackOutcome.IGNORED;
			}
		}
	}

	private CallbackOutcome conflict(Transfer transfer, String actor, Map<String, Object> detail) {
		transfer.flagForReconciliation();
		detail.put("recordedState", transfer.getState().name());
		audit(transfer, "CALLBACK_CONFLICT", transfer.getState(), actor, detail);
		return CallbackOutcome.CONFLICT;
	}

	private void audit(Transfer transfer, String eventType, TransferState from, String actor,
			Map<String, Object> detail) {
		this.events.save(TransferEvent.of(transfer.getId(), eventType, from, transfer.getState(), actor, detail));
	}

}
