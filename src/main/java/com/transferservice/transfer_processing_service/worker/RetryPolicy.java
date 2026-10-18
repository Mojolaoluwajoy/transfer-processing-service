package com.transferservice.transfer_processing_service.worker;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Capped exponential backoff for unresolved external calls.
 */
@Component
class RetryPolicy {

	private final long initialMs;

	private final long maxMs;

	private final Duration lease;

	RetryPolicy(@Value("${worker.retry.initial-ms}") long initialMs, @Value("${worker.retry.max-ms}") long maxMs,
			@Value("${worker.lease-seconds}") int leaseSeconds) {
		this.initialMs = initialMs;
		this.maxMs = maxMs;
		this.lease = Duration.ofSeconds(leaseSeconds);
	}

	Instant nextAttempt(int attemptsSoFar) {
		return Instant.now().plusMillis(Math.min(this.initialMs << Math.min(attemptsSoFar, 16), this.maxMs));
	}

	/** Safety net while an external call is in flight: if the worker dies, recovery looks after the lease ends. */
	Instant afterLease() {
		return Instant.now().plus(this.lease);
	}

	int leaseSeconds() {
		return (int) this.lease.toSeconds();
	}

}
