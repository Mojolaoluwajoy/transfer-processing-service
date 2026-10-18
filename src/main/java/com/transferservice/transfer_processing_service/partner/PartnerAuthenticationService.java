package com.transferservice.transfer_processing_service.partner;

import java.util.Optional;

import com.transferservice.transfer_processing_service.common.Hashing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerAuthenticationService {

	private final JdbcClient jdbc;

	PartnerAuthenticationService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Resolves a raw API key to an active partner. Only key hashes are stored or compared. */
	@Transactional(readOnly = true)
	public Optional<PartnerPrincipal> authenticate(String rawApiKey) {
		return this.jdbc.sql("""
				SELECT p.id AS partner_id, p.name
				FROM partner_api_keys k
				JOIN partners p ON p.id = k.partner_id
				WHERE k.key_hash = :hash AND k.revoked_at IS NULL AND p.status = 'ACTIVE'
				""")
			.param("hash", Hashing.sha256Hex(rawApiKey))
			.query(PartnerPrincipal.class)
			.optional();
	}

}
