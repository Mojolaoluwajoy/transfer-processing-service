package com.transferservice.transfer_processing_service.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A transfer that needs a human, with everything needed to decide what really happened.
 */
public record ReconciliationItem(String reference, UUID partnerId, String externalReference, String state,
		String amount, String currency, String failureReason, Instant updatedAt, List<Issue> issues,
		List<Operation> operations, List<Callback> callbacks) {

	/** An audit event explaining why the transfer was flagged. */
	public record Issue(String eventType, String actor, Map<String, Object> detail, Instant at) {
	}

	public record Operation(String type, String reference, String status, String externalId, int attempts,
			String lastError) {
	}

	public record Callback(String eventId, String reportedStatus, String outcome, Instant receivedAt) {
	}

}
