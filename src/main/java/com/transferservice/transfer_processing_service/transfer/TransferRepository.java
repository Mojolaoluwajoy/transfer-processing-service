package com.transferservice.transfer_processing_service.transfer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

	Optional<Transfer> findByPartnerIdAndExternalReference(UUID partnerId, String externalReference);

	Optional<Transfer> findByPartnerIdAndReference(UUID partnerId, String reference);

}
