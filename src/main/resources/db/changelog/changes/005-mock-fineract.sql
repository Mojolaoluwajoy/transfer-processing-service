--liquibase formatted sql

--changeset transfer-service:005-create-mock-fineract
CREATE TABLE mock_fineract.accounts (
    account_number    VARCHAR(20) PRIMARY KEY,
    currency          CHAR(3)        NOT NULL DEFAULT 'NGN',
    -- The CHECK is the last line of defence; debits use a conditional UPDATE.
    available_balance NUMERIC(19, 2) NOT NULL CHECK (available_balance >= 0),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Every debit/refund keyed by the caller's operation reference.
-- A repeat with the same request_hash returns this row; a different hash is rejected.
CREATE TABLE mock_fineract.operations (
    operation_reference VARCHAR(60) PRIMARY KEY,
    operation_type      VARCHAR(20)    NOT NULL,
    account_number      VARCHAR(20)    NOT NULL REFERENCES mock_fineract.accounts (account_number),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    request_hash        CHAR(64)       NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    rejection_reason    VARCHAR(100),
    original_reference  VARCHAR(60),
    transaction_id      VARCHAR(60),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Only one refund can ever exist for a given debit.
CREATE UNIQUE INDEX uq_mock_fineract_one_refund_per_debit
    ON mock_fineract.operations (original_reference) WHERE operation_type = 'REFUND';
