package com.transferservice.transfer_processing_service.transfer;

import java.util.Optional;

import com.transferservice.transfer_processing_service.partner.PartnerAccess;
import com.transferservice.transfer_processing_service.partner.PartnerPrincipal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class TransferService {

	private final TransferRepository transfers;

	private final TransferCreator creator;

	private final PartnerAccess partnerAccess;

	TransferService(TransferRepository transfers, TransferCreator creator, PartnerAccess partnerAccess) {
		this.transfers = transfers;
		this.creator = creator;
		this.partnerAccess = partnerAccess;
	}

	/**
	 * Accepts a transfer exactly once per partner and external reference.
	 * <p>
	 * Deliberately not transactional: the insert runs in its own transaction so that, when a
	 * concurrent request wins the unique constraint, this one can read the winner in a fresh
	 * transaction (Postgres aborts a transaction after a constraint violation).
	 */
	public Initiation initiate(PartnerPrincipal partner, NewTransfer request) {
		this.partnerAccess.requireSourceAccount(partner.partnerId(), request.sourceAccount());
		String requestHash = request.requestHash();

		Optional<Transfer> existing = findExisting(partner, request);
		if (existing.isPresent()) {
			return replay(existing.get(), requestHash);
		}
		try {
			return new Initiation(this.creator.create(partner.partnerId(), request, requestHash), true);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost the race to a concurrent request with the same external reference.
			return replay(findExisting(partner, request).orElseThrow(() -> ex), requestHash);
		}
	}

	public Transfer get(PartnerPrincipal partner, String reference) {
		return this.transfers.findByPartnerIdAndReference(partner.partnerId(), reference)
			.orElseThrow(() -> new TransferNotFoundException(reference));
	}

	private Optional<Transfer> findExisting(PartnerPrincipal partner, NewTransfer request) {
		return this.transfers.findByPartnerIdAndExternalReference(partner.partnerId(), request.externalReference());
	}

	private static Initiation replay(Transfer existing, String requestHash) {
		if (!existing.getRequestHash().equals(requestHash)) {
			throw new IdempotencyConflictException(existing.getExternalReference());
		}
		return new Initiation(existing, false);
	}

	public record Initiation(Transfer transfer, boolean created) {
	}

}
