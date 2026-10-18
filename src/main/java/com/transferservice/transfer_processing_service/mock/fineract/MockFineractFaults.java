package com.transferservice.transfer_processing_service.mock.fineract;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Fault injection for tests and demos. Kept in memory on purpose: it is test control state,
 * not financial state.
 */
@Component
class MockFineractFaults {

	private final AtomicInteger remainingDelayedResponses = new AtomicInteger();

	private volatile long delayMs;

	void delayNextResponses(int count, long delayMs) {
		this.delayMs = delayMs;
		this.remainingDelayedResponses.set(count);
	}

	void clear() {
		this.remainingDelayedResponses.set(0);
	}

	/** Called after the operation's transaction has committed. */
	void afterCommit() {
		if (this.remainingDelayedResponses.getAndUpdate((n) -> Math.max(n - 1, 0)) > 0) {
			try {
				Thread.sleep(this.delayMs);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

}
