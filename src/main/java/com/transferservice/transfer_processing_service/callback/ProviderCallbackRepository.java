package com.transferservice.transfer_processing_service.callback;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProviderCallbackRepository extends JpaRepository<ProviderCallback, Long> {

	/**
	 * Stores the callback unless its event id was seen before. A concurrent delivery of the
	 * same event waits on the unique index, then inserts nothing.
	 * @return 1 if stored, 0 if it is a duplicate
	 */
	@Modifying
	@Query(value = """
			INSERT INTO provider_callbacks (provider_event_id, payment_reference, reported_status, payload)
			VALUES (:eventId, :paymentReference, :status, CAST(:payload AS jsonb))
			ON CONFLICT (provider_event_id) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(String eventId, String paymentReference, String status, String payload);

	Optional<ProviderCallback> findByProviderEventId(String providerEventId);

	List<ProviderCallback> findByPaymentReferenceOrderByIdAsc(String paymentReference);

}
