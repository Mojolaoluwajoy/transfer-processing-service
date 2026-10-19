package com.transferservice.transfer_processing_service.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FinancialOperationRepository extends JpaRepository<FinancialOperation, Long> {

	Optional<FinancialOperation> findByTransferIdAndOperationType(UUID transferId, FinancialOperation.Type type);

	List<FinancialOperation> findByTransferIdOrderByIdAsc(UUID transferId);

	/** Resolves an operation reference to its transfer without loading (and caching) the operation. */
	@Query("SELECT o.transferId FROM FinancialOperation o WHERE o.operationReference = :reference AND o.operationType = :type")
	Optional<UUID> findTransferIdByReference(String reference, FinancialOperation.Type type);

}
