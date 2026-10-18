package com.transferservice.transfer_processing_service;

import java.math.BigDecimal;
import java.util.UUID;

import com.transferservice.transfer_processing_service.common.Hashing;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Creates partners with an API key and authorised source accounts for tests.
 */
public final class TestPartners {

	private TestPartners() {
	}

	public static TestPartner create(JdbcClient jdbc, String... sourceAccounts) {
		UUID partnerId = UUID.randomUUID();
		String apiKey = "test-key-" + UUID.randomUUID();
		jdbc.sql("INSERT INTO partners (id, name) VALUES (:id, :name)")
			.param("id", partnerId)
			.param("name", "Partner " + partnerId)
			.update();
		jdbc.sql("""
				INSERT INTO partner_api_keys (id, partner_id, key_hash, key_prefix)
				VALUES (:id, :partnerId, :hash, :prefix)
				""")
			.param("id", UUID.randomUUID())
			.param("partnerId", partnerId)
			.param("hash", Hashing.sha256Hex(apiKey))
			.param("prefix", apiKey.substring(0, 12))
			.update();
		for (String account : sourceAccounts) {
			jdbc.sql("INSERT INTO partner_accounts (partner_id, account_number) VALUES (:partnerId, :account)")
				.param("partnerId", partnerId)
				.param("account", account)
				.update();
		}
		return new TestPartner(partnerId, apiKey);
	}

	/** Creates or resets an account in the mock Fineract ledger. */
	public static void fund(JdbcClient jdbc, String accountNumber, String balance) {
		jdbc.sql("""
				INSERT INTO mock_fineract.accounts (account_number, available_balance) VALUES (:account, :balance)
				ON CONFLICT (account_number) DO UPDATE SET available_balance = :balance
				""").param("account", accountNumber).param("balance", new BigDecimal(balance)).update();
	}

	public record TestPartner(UUID id, String apiKey) {
	}

}
