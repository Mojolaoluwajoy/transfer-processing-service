package com.transferservice.transfer_processing_service.worker;

import java.util.List;
import java.util.UUID;

import com.transferservice.transfer_processing_service.transfer.TransferRepository;
import com.transferservice.transfer_processing_service.transfer.TransferState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumes transfers that nobody is working on and whose retry time has come: unresolved
 * external calls, workers that died mid-step (expired lease), and accepted transfers whose
 * message never arrived. Safe on every instance at once, because processing starts by
 * claiming the lease.
 */
@Component
class TransferRecovery {

	/** States with an automatic next step. */
	static final List<String> ACTIONABLE_STATES = List.of(TransferState.RECEIVED.name(),
			TransferState.DEBIT_PENDING.name());

	private static final Logger log = LoggerFactory.getLogger(TransferRecovery.class);

	private final TransferRepository transfers;

	private final TransferProcessor processor;

	private final int batchSize;

	TransferRecovery(TransferRepository transfers, TransferProcessor processor,
			@Value("${worker.recovery.batch-size}") int batchSize) {
		this.transfers = transfers;
		this.processor = processor;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${worker.recovery.poll-ms}")
	void recoverDue() {
		for (UUID transferId : this.transfers.findRecoverable(ACTIONABLE_STATES, this.batchSize)) {
			try {
				this.processor.process(transferId);
			}
			catch (RuntimeException ex) {
				log.error("Recovery of transfer {} failed; will retry after its lease expires", transferId, ex);
			}
		}
	}

}
