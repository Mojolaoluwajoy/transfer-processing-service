package com.transferservice.transfer_processing_service.transfer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestcontainersConfiguration;
import com.transferservice.transfer_processing_service.outbox.OutboxMessageRepository;
import com.transferservice.transfer_processing_service.transfer.api.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferApiTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransferRepository transfers;

	@Autowired
	TransferEventRepository events;

	@Autowired
	OutboxMessageRepository outbox;

	RestClient client;

	String sourceAccount;

	TestPartner partner;

	@BeforeEach
	void setUp() {
		this.client = RestClient.builder().baseUrl("http://localhost:" + this.port).build();
		this.sourceAccount = randomAccount();
		this.partner = TestPartners.create(this.jdbc, this.sourceAccount);
	}

	@Test
	void acceptsTransferAndRecordsAuditAndOutboxAtomically() {
		ResponseEntity<TransferResponse> response = post(this.partner, request("merchant-000001"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		TransferResponse body = response.getBody();
		assertThat(body.state()).isEqualTo("RECEIVED");
		assertThat(body.amount()).isEqualTo("25000.00");
		assertThat(response.getHeaders().getLocation()).hasToString("/api/v2/transfers/" + body.reference());

		Transfer transfer = this.transfers.findByPartnerIdAndReference(this.partner.id(), body.reference()).orElseThrow();
		// The worker may already have moved the transfer on; acceptance is always the first event.
		assertThat(this.events.findByTransferIdOrderByIdAsc(transfer.getId())).first().satisfies((event) -> {
			assertThat(event.getEventType()).isEqualTo("TRANSFER_ACCEPTED");
			assertThat(event.getToState()).isEqualTo(TransferState.RECEIVED);
		});
		assertThat(this.outbox.findByAggregateId(transfer.getId())).singleElement()
			.satisfies((message) -> assertThat(message.getPayload()).containsEntry("reference", body.reference()));
	}

	@Test
	void statusLookupReturnsTransfer() {
		TransferResponse created = post(this.partner, request("merchant-000002")).getBody();

		TransferResponse fetched = get(this.partner, created.reference());

		assertThat(fetched.reference()).isEqualTo(created.reference());
		assertThat(fetched.externalReference()).isEqualTo("merchant-000002");
	}

	@Test
	void identicalRepeatReturnsExistingTransfer() {
		Map<String, Object> request = request("merchant-000003");
		TransferResponse first = post(this.partner, request).getBody();

		ResponseEntity<TransferResponse> repeat = post(this.partner, request);

		assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(repeat.getBody().reference()).isEqualTo(first.reference());
	}

	@Test
	void numericallyEqualAmountIsTheSameRequest() {
		Map<String, Object> request = request("merchant-000004");
		post(this.partner, request);
		request.put("amount", "25000");

		assertThat(post(this.partner, request).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void reusingExternalReferenceWithDifferentPayloadIsRejected() {
		Map<String, Object> request = request("merchant-000005");
		post(this.partner, request);
		request.put("amount", "25000.01");

		HttpClientErrorException ex = postExpectingError(this.partner, request);

		assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(ex.getResponseBodyAsString()).contains("IDEMPOTENCY_CONFLICT");
	}

	@Test
	void idempotencyIsScopedToPartner() {
		String otherAccount = randomAccount();
		TestPartner other = TestPartners.create(this.jdbc, otherAccount);
		post(this.partner, request("merchant-000006"));
		Map<String, Object> otherRequest = request("merchant-000006");
		otherRequest.put("sourceAccount", otherAccount);

		assertThat(post(other, otherRequest).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	}

	@Test
	void sourceAccountMustBelongToPartner() {
		Map<String, Object> request = request("merchant-000007");
		request.put("sourceAccount", randomAccount());

		HttpClientErrorException ex = postExpectingError(this.partner, request);

		assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ex.getResponseBodyAsString()).contains("SOURCE_ACCOUNT_NOT_PERMITTED");
	}

	@Test
	void anotherPartnersTransferIsNotFound() {
		TransferResponse created = post(this.partner, request("merchant-000008")).getBody();
		TestPartner other = TestPartners.create(this.jdbc, randomAccount());

		try {
			get(other, created.reference());
			throw new AssertionError("Expected 404");
		}
		catch (HttpClientErrorException ex) {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = ';', value = { "amount;0", "amount;-5.00", "amount;10.001", "currency;USD",
			"sourceAccount;12345", "destinationBankCode;ABC", "externalReference;has space" })
	void invalidFieldsAreRejected(String field, String value) {
		Map<String, Object> request = request("merchant-invalid");
		request.put(field, value);

		HttpClientErrorException ex = postExpectingError(this.partner, request);

		assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ex.getResponseBodyAsString()).contains("VALIDATION_FAILED").contains(field);
	}

	@Test
	void nonNumericAmountIsMalformed() {
		Map<String, Object> request = request("merchant-malformed");
		request.put("amount", "lots");

		HttpClientErrorException ex = postExpectingError(this.partner, request);

		assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ex.getResponseBodyAsString()).contains("MALFORMED_REQUEST");
	}

	/** Mandatory scenario 1 (API half): 20 simultaneous identical requests create one transfer. */
	@Test
	void twentySimultaneousIdenticalRequestsCreateOneTransfer() throws Exception {
		Map<String, Object> request = request("merchant-concurrent");
		ExecutorService pool = Executors.newFixedThreadPool(20);
		List<ResponseEntity<TransferResponse>> responses = new ArrayList<>();
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<ResponseEntity<TransferResponse>>> futures = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return post(this.partner, request);
				}));
			}
			start.countDown();
			for (Future<ResponseEntity<TransferResponse>> future : futures) {
				responses.add(future.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(responses).filteredOn((r) -> r.getStatusCode() == HttpStatus.ACCEPTED).hasSize(1);
		assertThat(responses).filteredOn((r) -> r.getStatusCode() == HttpStatus.OK).hasSize(19);
		assertThat(responses).extracting((r) -> r.getBody().reference()).containsOnly(responses.get(0).getBody().reference());
		Transfer transfer = this.transfers.findByPartnerIdAndExternalReference(this.partner.id(), "merchant-concurrent")
			.orElseThrow();
		assertThat(this.jdbc.sql("SELECT count(*) FROM transfers WHERE partner_id = :id")
			.param("id", this.partner.id())
			.query(Long.class)
			.single()).isEqualTo(1);
		assertThat(this.outbox.findByAggregateId(transfer.getId())).hasSize(1);
		assertThat(this.events.findByTransferIdOrderByIdAsc(transfer.getId()))
			.filteredOn((event) -> event.getEventType().equals("TRANSFER_ACCEPTED"))
			.hasSize(1);
	}

	private Map<String, Object> request(String externalReference) {
		Map<String, Object> request = new HashMap<>();
		request.put("externalReference", externalReference);
		request.put("sourceAccount", this.sourceAccount);
		request.put("destinationAccount", "0123456789");
		request.put("destinationBankCode", "000004");
		request.put("amount", "25000.00");
		request.put("currency", "NGN");
		request.put("narration", "Invoice payment");
		return request;
	}

	private ResponseEntity<TransferResponse> post(TestPartner as, Map<String, Object> request) {
		return this.client.post().uri("/api/v2/transfers").header("X-API-Key", as.apiKey()).body(request).retrieve()
			.toEntity(TransferResponse.class);
	}

	private HttpClientErrorException postExpectingError(TestPartner as, Map<String, Object> request) {
		try {
			post(as, request);
		}
		catch (HttpClientErrorException ex) {
			return ex;
		}
		throw new AssertionError("Expected a client error");
	}

	private TransferResponse get(TestPartner as, String reference) {
		return this.client.get().uri("/api/v2/transfers/{ref}", reference).header("X-API-Key", as.apiKey()).retrieve()
			.body(TransferResponse.class);
	}

	private static String randomAccount() {
		return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
	}

}
