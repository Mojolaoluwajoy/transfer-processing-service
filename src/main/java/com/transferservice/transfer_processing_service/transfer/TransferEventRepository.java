package com.transferservice.transfer_processing_service.transfer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferEventRepository extends JpaRepository<TransferEvent, Long> {

	List<TransferEvent> findByTransferIdOrderByIdAsc(UUID transferId);

}
