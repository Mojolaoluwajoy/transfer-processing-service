package com.transferservice.transfer_processing_service.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

	List<OutboxMessage> findByAggregateId(UUID aggregateId);

	/**
	 * Locks a batch of due, unpublished messages. Rows locked by another relay instance are
	 * skipped rather than waited on, so instances share the work without double-publishing.
	 */
	@Query(value = """
			SELECT * FROM outbox_messages
			WHERE published_at IS NULL AND next_attempt_at <= now()
			ORDER BY id
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxMessage> lockDueBatch(int limit);

}
