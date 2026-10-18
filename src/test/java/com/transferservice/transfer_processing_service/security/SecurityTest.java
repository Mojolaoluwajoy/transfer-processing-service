package com.transferservice.transfer_processing_service.security;

import java.util.UUID;

import com.transferservice.transfer_processing_service.TestPartners;
import com.transferservice.transfer_processing_service.TestPartners.TestPartner;
import com.transferservice.transfer_processing_service.TestcontainersConfiguration;
import com.transferservice.transfer_processing_service.partner.PartnerAccess;
import com.transferservice.transfer_processing_service.partner.SourceAccountNotPermittedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartnerAccess partnerAccess;

	RestClient client;

	TestPartner partner;

	@BeforeEach
	void setUp() {
		this.client = RestClient.builder().baseUrl("http://localhost:" + this.port).build();
		this.partner = TestPartners.create(this.jdbc, "SRC-" + UUID.randomUUID().toString().substring(0, 8));
	}

	@Test
	void partnerApiWithoutKeyIsUnauthorizedWithProblemJson() {
		assertStatus(null, HttpStatus.UNAUTHORIZED);
		HttpClientErrorException ex = call(null);
		assertThat(ex.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(ex.getResponseBodyAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
	}

	@Test
	void unknownKeyIsUnauthorized() {
		assertStatus("not-a-real-key", HttpStatus.UNAUTHORIZED);
	}

	@Test
	void revokedKeyIsUnauthorized() {
		this.jdbc.sql("UPDATE partner_api_keys SET revoked_at = now() WHERE partner_id = :id")
			.param("id", this.partner.id())
			.update();
		assertStatus(this.partner.apiKey(), HttpStatus.UNAUTHORIZED);
	}

	@Test
	void suspendedPartnerIsUnauthorized() {
		this.jdbc.sql("UPDATE partners SET status = 'SUSPENDED' WHERE id = :id").param("id", this.partner.id()).update();
		assertStatus(this.partner.apiKey(), HttpStatus.UNAUTHORIZED);
	}

	@Test
	void validKeyPassesAuthentication() {
		// No transfer endpoint exists yet, so getting past security means a 404, not a 401.
		assertStatus(this.partner.apiKey(), HttpStatus.NOT_FOUND);
	}

	@Test
	void mocksAndHealthAreOpenButUnknownPathsAreDenied() {
		assertThat(this.client.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThatThrownBy(() -> this.client.get().uri("/mock/fineract/operations/none").retrieve().toBodilessEntity())
			.isInstanceOfSatisfying(HttpClientErrorException.class,
					(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThatThrownBy(() -> this.client.get().uri("/somewhere-else").retrieve().toBodilessEntity())
			.isInstanceOfSatisfying(HttpClientErrorException.class,
					(ex) -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
	}

	@Test
	void partnerMayOnlyDebitItsOwnAccounts() {
		TestPartner other = TestPartners.create(this.jdbc, "SRC-" + UUID.randomUUID().toString().substring(0, 8));
		String ownAccount = this.jdbc.sql("SELECT account_number FROM partner_accounts WHERE partner_id = :id")
			.param("id", this.partner.id())
			.query(String.class)
			.single();

		assertThat(this.partnerAccess.canDebit(this.partner.id(), ownAccount)).isTrue();
		assertThat(this.partnerAccess.canDebit(other.id(), ownAccount)).isFalse();
		assertThatThrownBy(() -> this.partnerAccess.requireSourceAccount(other.id(), ownAccount))
			.isInstanceOf(SourceAccountNotPermittedException.class);
	}

	private void assertStatus(String apiKey, HttpStatus expected) {
		assertThat(call(apiKey).getStatusCode()).isEqualTo(expected);
	}

	private HttpClientErrorException call(String apiKey) {
		try {
			this.client.get()
				.uri("/api/v2/transfers/{ref}", "TRF-unknown")
				.headers((headers) -> {
					if (apiKey != null) {
						headers.set("X-API-Key", apiKey);
					}
				})
				.retrieve()
				.toBodilessEntity();
		}
		catch (HttpClientErrorException ex) {
			return ex;
		}
		throw new AssertionError("Expected a client error");
	}

}
