package com.transferservice.transfer_processing_service.transfer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.transferservice.transfer_processing_service.outbox.OutboxMessage;
import com.transferservice.transfer_processing_service.outbox.OutboxMessageRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a new transfer, its first audit event and its outbox message in one transaction.
 */
@Component
class TransferCreator {

	static final String TRANSFER_ACCEPTED = "TRANSFER_ACCEPTED";

	private final TransferRepository transfers;

	private final TransferEventRepository events;

	private final OutboxMessageRepository outbox;

	TransferCreator(TransferRepository transfers, TransferEventRepository events, OutboxMessageRepository outbox) {
		this.transfers = transfers;
		this.events = events;
		this.outbox = outbox;
	}

	/**
	 * @throws org.springframework.dao.DataIntegrityViolationException if the partner already
	 * has a transfer with this external reference (raised at flush, before anything else is written)
	 */
	@Transactional
	public Transfer create(UUID partnerId, NewTransfer request, String requestHash) {
		Transfer transfer = this.transfers.saveAndFlush(Transfer.received(partnerId, request.externalReference(),
				requestHash, request.sourceAccount(), request.destinationAccount(), request.destinationBankCode(),
				request.amount(), request.currency(), request.narration(), Instant.now()));
		this.events.save(TransferEvent.of(transfer.getId(), TRANSFER_ACCEPTED, null, TransferState.RECEIVED,
				"partner:" + partnerId, Map.of("externalReference", request.externalReference(), "amount",
						request.amount().toPlainString(), "currency", request.currency())));
		this.outbox.save(OutboxMessage.of(transfer.getId(), TRANSFER_ACCEPTED,
				Map.of("transferId", transfer.getId().toString(), "reference", transfer.getReference())));
		return transfer;
	}

}
