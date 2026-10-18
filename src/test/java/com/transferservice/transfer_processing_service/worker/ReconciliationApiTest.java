package com.transferservice.transfer_processing_service.worker;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.transferservice.transfer_processing_service.transfer.TransferState;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import org.junit.jupiter.api.Test;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Operator view of transfers flagged for reconciliation.
 */
class ReconciliationApiTest extends WorkerTestSupport {

	static final String OPERATOR_KEY = "dev-operator-key";

	@Test
	void flaggedTransferIsListedWithItsEvidence() {
		TransferResponse transfer = accept("Invoice SIM:CONFLICTING_CALLBACKS");
		awaitState(transfer.reference(), TransferState.COMPLETED);
		await().atMost(Duration.ofSeconds(10)).until(() -> transfer(transfer.reference()).isReconciliationRequired());

		Map<String, Object> item = flagged(OPERATOR_KEY).stream()
			.filter((i) -> transfer.reference().equals(i.get("reference")))
			.findFirst()
			.orElseThrow();

		assertThat(item).containsEntry("state", "COMPLETED").containsEntry("amount", "25000.00");
		assertThat(listOfMaps(item, "issues")).extracting((i) -> i.get("eventType")).contains("CALLBACK_CONFLICT");
		assertThat(listOfMaps(item, "operations")).extracting((o) -> o.get("type")).containsExactly("DEBIT", "PAYMENT");
		assertThat(listOfMaps(item, "callbacks")).extracting((c) -> c.get("outcome")).contains("CONFLICT");
	}

	@Test
	void transfersWithoutConflictsAreNotListed() {
		TransferResponse transfer = accept();
		awaitState(transfer.reference(), TransferState.COMPLETED);

		assertThat(flagged(OPERATOR_KEY)).noneMatch((i) -> transfer.reference().equals(i.get("reference")));
	}

	@Test
	void requiresOperatorKey() {
		assertThatThrownBy(() -> this.client.get().uri("/api/v2/ops/reconciliation").retrieve().toBodilessEntity())
			.isInstanceOfSatisfying(HttpClientErrorException.class,
					(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
		assertThatThrownBy(() -> flagged("wrong-key")).isInstanceOfSatisfying(HttpClientErrorException.class,
				(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
		// A partner is authenticated but not authorised for operator endpoints.
		assertThatThrownBy(() -> this.client.get()
			.uri("/api/v2/ops/reconciliation")
			.header("X-API-Key", this.partner.apiKey())
			.retrieve()
			.toBodilessEntity()).isInstanceOfSatisfying(HttpClientErrorException.class,
					(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void operatorKeyDoesNotGrantPartnerAccess() {
		assertThatThrownBy(() -> this.client.get()
			.uri("/api/v2/transfers/{ref}", "TRF-anything")
			.header("X-Operator-Key", OPERATOR_KEY)
			.retrieve()
			.toBodilessEntity()).isInstanceOfSatisfying(HttpClientErrorException.class,
					(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	private List<Map<String, Object>> flagged(String operatorKey) {
		return this.client.get()
			.uri("/api/v2/ops/reconciliation")
			.header("X-Operator-Key", operatorKey)
			.retrieve()
			.body(new ParameterizedTypeReference<>() {
			});
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> listOfMaps(Map<String, Object> item, String key) {
		return (List<Map<String, Object>>) item.get(key);
	}

}
