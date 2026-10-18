package com.transferservice.transfer_processing_service.partner;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorisation of partners over source accounts.
 */
@Service
public class PartnerAccess {

	private final JdbcClient jdbc;

	PartnerAccess(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public boolean canDebit(UUID partnerId, String sourceAccount) {
		return this.jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM partner_accounts WHERE partner_id = :partnerId AND account_number = :account)
				""").param("partnerId", partnerId).param("account", sourceAccount).query(Boolean.class).single();
	}

	public void requireSourceAccount(UUID partnerId, String sourceAccount) {
		if (!canDebit(partnerId, sourceAccount)) {
			throw new SourceAccountNotPermittedException(sourceAccount);
		}
	}

}
