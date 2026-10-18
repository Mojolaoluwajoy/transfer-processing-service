package com.transferservice.transfer_processing_service.reconciliation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator view of transfers that need a human. Requires {@code ROLE_OPERATOR}.
 */
@RestController
class ReconciliationController {

	private final ReconciliationService service;

	ReconciliationController(ReconciliationService service) {
		this.service = service;
	}

	@GetMapping("/api/v2/ops/reconciliation")
	List<ReconciliationItem> flagged() {
		return this.service.flagged();
	}

}
