package com.transferservice.transfer_processing_service.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialOperationRepository extends JpaRepository<FinancialOperation, Long> {

	Optional<FinancialOperation> findByTransferIdAndOperationType(UUID transferId, FinancialOperation.Type type);

	List<FinancialOperation> findByTransferIdOrderByIdAsc(UUID transferId);

}
