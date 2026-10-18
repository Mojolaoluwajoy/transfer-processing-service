package com.transferservice.transfer_processing_service.mock.fineract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.transferservice.transfer_processing_service.common.Hashing;
import com.transferservice.transfer_processing_service.mock.MockApiException;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.AccountResponse;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.DebitRequest;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.OperationResponse;
import com.transferservice.transfer_processing_service.mock.fineract.MockFineractApi.RefundRequest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock core-banking ledger. Each operation is keyed by the caller's operation reference and
 * resolves to exactly one durable outcome.
 */
@Service
class MockFineractService {

	private final JdbcClient jdbc;

	MockFineractService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public OperationResponse debit(DebitRequest request) {
		BigDecimal amount = normalize(request.amount());
		String hash = Hashing.sha256Hex("DEBIT|" + request.accountNumber() + "|" + amount.toPlainString());
		requireAccount(request.accountNumber());

		// Claim the reference first. A concurrent insert of the same reference blocks here until
		// the other transaction finishes, then inserts nothing and we return its stored outcome.
		int inserted = this.jdbc.sql("""
				INSERT INTO mock_fineract.operations
				    (operation_reference, operation_type, account_number, amount, request_hash, status, transaction_id)
				VALUES (:ref, 'DEBIT', :account, :amount, :hash, 'COMMITTED', :txn)
				ON CONFLICT (operation_reference) DO NOTHING
				""")
			.param("ref", request.operationReference())
			.param("account", request.accountNumber())
			.param("amount", amount)
			.param("hash", hash)
			.param("txn", newTransactionId())
			.update();
		if (inserted == 0) {
			return replay(request.operationReference(), hash);
		}

		// Atomic funds check: the row lock serialises concurrent debits on the same account.
		int debited = this.jdbc.sql("""
				UPDATE mock_fineract.accounts
				SET available_balance = available_balance - :amount, updated_at = now()
				WHERE account_number = :account AND available_balance >= :amount
				""").param("amount", amount).param("account", request.accountNumber()).update();
		if (debited == 0) {
			this.jdbc.sql("""
					UPDATE mock_fineract.operations
					SET status = 'REJECTED', rejection_reason = 'INSUFFICIENT_FUNDS', transaction_id = NULL
					WHERE operation_reference = :ref
					""").param("ref", request.operationReference()).update();
		}
		return toResponse(findOperation(request.operationReference()).orElseThrow(), false);
	}

	@Transactional
	public OperationResponse refund(RefundRequest request) {
		BigDecimal amount = normalize(request.amount());
		String hash = Hashing.sha256Hex("REFUND|" + request.originalReference() + "|" + amount.toPlainString());

		Optional<Operation> existing = findOperation(request.operationReference());
		if (existing.isPresent()) {
			return replay(existing.get(), hash);
		}

		Operation original = findOperation(request.originalReference())
			.orElseThrow(() -> MockApiException.notFound("ORIGINAL_NOT_FOUND",
					"No operation " + request.originalReference()));
		if (!"DEBIT".equals(original.operationType()) || !"COMMITTED".equals(original.status())) {
			throw MockApiException.unprocessable("ORIGINAL_NOT_REFUNDABLE",
					"Only committed debits can be refunded");
		}
		if (original.amount().compareTo(amount) != 0) {
			throw MockApiException.unprocessable("AMOUNT_MISMATCH", "Refund must equal the debited amount");
		}

		int inserted;
		try {
			inserted = this.jdbc.sql("""
					INSERT INTO mock_fineract.operations
					    (operation_reference, operation_type, account_number, amount, request_hash, status,
					     original_reference, transaction_id)
					VALUES (:ref, 'REFUND', :account, :amount, :hash, 'COMMITTED', :original, :txn)
					ON CONFLICT (operation_reference) DO NOTHING
					""")
				.param("ref", request.operationReference())
				.param("account", original.accountNumber())
				.param("amount", amount)
				.param("hash", hash)
				.param("original", request.originalReference())
				.param("txn", newTransactionId())
				.update();
		}
		catch (DuplicateKeyException ex) {
			// The partial unique index allows one refund per debit, whatever reference it uses.
			throw MockApiException.conflict("ALREADY_REFUNDED",
					"Debit " + request.originalReference() + " already has a refund");
		}
		if (inserted == 0) {
			return replay(request.operationReference(), hash);
		}

		this.jdbc.sql("""
				UPDATE mock_fineract.accounts
				SET available_balance = available_balance + :amount, updated_at = now()
				WHERE account_number = :account
				""").param("amount", amount).param("account", original.accountNumber()).update();
		return toResponse(findOperation(request.operationReference()).orElseThrow(), false);
	}

	@Transactional(readOnly = true)
	public OperationResponse getOperation(String operationReference) {
		return findOperation(operationReference).map((op) -> toResponse(op, false))
			.orElseThrow(() -> MockApiException.notFound("OPERATION_NOT_FOUND",
					"No operation " + operationReference));
	}

	@Transactional
	public AccountResponse upsertAccount(String accountNumber, BigDecimal balance) {
		this.jdbc.sql("""
				INSERT INTO mock_fineract.accounts (account_number, available_balance)
				VALUES (:account, :balance)
				ON CONFLICT (account_number) DO UPDATE SET available_balance = :balance, updated_at = now()
				""").param("account", accountNumber).param("balance", normalize(balance)).update();
		return getAccount(accountNumber);
	}

	@Transactional(readOnly = true)
	public AccountResponse getAccount(String accountNumber) {
		return this.jdbc.sql("""
				SELECT account_number, currency, available_balance FROM mock_fineract.accounts
				WHERE account_number = :account
				""")
			.param("account", accountNumber)
			.query(AccountResponse.class)
			.optional()
			.orElseThrow(() -> MockApiException.notFound("ACCOUNT_NOT_FOUND", "No account " + accountNumber));
	}

	@Transactional(readOnly = true)
	public long countOperations(String operationType, String status) {
		return this.jdbc.sql("""
				SELECT count(*) FROM mock_fineract.operations WHERE operation_type = :type AND status = :status
				""").param("type", operationType).param("status", status).query(Long.class).single();
	}

	private OperationResponse replay(String operationReference, String requestHash) {
		return replay(findOperation(operationReference).orElseThrow(), requestHash);
	}

	private OperationResponse replay(Operation existing, String requestHash) {
		if (!existing.requestHash().equals(requestHash)) {
			throw MockApiException.conflict("REFERENCE_CONFLICT",
					"Operation reference " + existing.operationReference() + " was used for a different request");
		}
		return toResponse(existing, true);
	}

	private void requireAccount(String accountNumber) {
		getAccount(accountNumber);
	}

	private Optional<Operation> findOperation(String operationReference) {
		return this.jdbc.sql("SELECT * FROM mock_fineract.operations WHERE operation_reference = :ref")
			.param("ref", operationReference)
			.query(Operation.class)
			.optional();
	}

	private static BigDecimal normalize(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.UNNECESSARY);
	}

	private static String newTransactionId() {
		return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
	}

	private static OperationResponse toResponse(Operation op, boolean replayed) {
		return new OperationResponse(op.operationReference(), op.operationType(), op.status(), op.rejectionReason(),
				op.accountNumber(), op.amount(), op.originalReference(), op.transactionId(), op.createdAt(), replayed);
	}

	record Operation(String operationReference, String operationType, String accountNumber, BigDecimal amount,
			String requestHash, String status, String rejectionReason, String originalReference,
			String transactionId, OffsetDateTime createdAt) {
	}

}
