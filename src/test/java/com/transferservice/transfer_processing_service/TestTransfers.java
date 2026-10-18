package com.transferservice.transfer_processing_service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Helpers for calling the partner transfer API from tests.
 */
public final class TestTransfers {

	private TestTransfers() {
	}

	public static Map<String, Object> request(String externalReference, String sourceAccount) {
		Map<String, Object> request = new HashMap<>();
		request.put("externalReference", externalReference);
		request.put("sourceAccount", sourceAccount);
		request.put("destinationAccount", "0123456789");
		request.put("destinationBankCode", "000004");
		request.put("amount", "25000.00");
		request.put("currency", "NGN");
		request.put("narration", "Invoice payment");
		return request;
	}

	public static ResponseEntity<TransferResponse> post(RestClient client, TestPartner as, Map<String, Object> request) {
		return client.post()
			.uri("/api/v2/transfers")
			.header("X-API-Key", as.apiKey())
			.body(request)
			.retrieve()
			.toEntity(TransferResponse.class);
	}

	public static TransferResponse get(RestClient client, TestPartner as, String reference) {
		return client.get()
			.uri("/api/v2/transfers/{ref}", reference)
			.header("X-API-Key", as.apiKey())
			.retrieve()
			.body(TransferResponse.class);
	}

	public static String randomAccount() {
		return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
	}

}
