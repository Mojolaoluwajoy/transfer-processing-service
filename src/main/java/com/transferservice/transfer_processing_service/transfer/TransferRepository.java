package com.transferservice.transfer_processing_service.transfer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

	Optional<Transfer> findByPartnerIdAndExternalReference(UUID partnerId, String externalReference);

	Optional<Transfer> findByPartnerIdAndReference(UUID partnerId, String reference);

	/** Loads the transfer with a row lock held until the surrounding transaction ends. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM Transfer t WHERE t.id = :id")
	Optional<Transfer> findByIdForUpdate(UUID id);

	/**
	 * Takes the processing lease if it is free or expired. Bumping the version means any
	 * worker still holding an older copy of the row fails its next write (fencing).
	 * @return 1 if the lease was taken, 0 if another worker holds it or the state is not eligible
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE transfers
			SET lease_owner = :owner, lease_expires_at = now() + :leaseSeconds * interval '1 second',
			    version = version + 1
			WHERE id = :id AND state IN (:states) AND (lease_owner IS NULL OR lease_expires_at < now())
			""", nativeQuery = true)
	int claimLease(UUID id, String owner, int leaseSeconds, Collection<String> states);

	@Modifying
	@Transactional
	@Query(value = """
			UPDATE transfers SET lease_owner = NULL, lease_expires_at = NULL, version = version + 1
			WHERE id = :id AND lease_owner = :owner
			""", nativeQuery = true)
	int releaseLease(UUID id, String owner);

	/** Transfers in an actionable state whose retry time has come and that nobody is working on. */
	@Query(value = """
			SELECT id FROM transfers
			WHERE state IN (:states) AND next_attempt_at <= now()
			  AND (lease_owner IS NULL OR lease_expires_at < now())
			ORDER BY next_attempt_at
			LIMIT :limit
			""", nativeQuery = true)
	List<UUID> findRecoverable(Collection<String> states, int limit);

}
