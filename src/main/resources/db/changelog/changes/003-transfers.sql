--liquibase formatted sql

--changeset transfer-service:003-create-transfers
CREATE TABLE transfers (
    id                      UUID PRIMARY KEY,
    reference               VARCHAR(40)    NOT NULL UNIQUE,
    partner_id              UUID           NOT NULL REFERENCES partners (id),
    external_reference      VARCHAR(100)   NOT NULL,
    request_hash            CHAR(64)       NOT NULL,
    source_account          VARCHAR(20)    NOT NULL,
    destination_account     VARCHAR(20)    NOT NULL,
    destination_bank_code   VARCHAR(10)    NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                CHAR(3)        NOT NULL CHECK (currency = 'NGN'),
    narration               VARCHAR(255),
    state                   VARCHAR(32)    NOT NULL,
    failure_reason          TEXT,
    reconciliation_required BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Optimistic locking: every state change bumps the version.
    version                 BIGINT         NOT NULL DEFAULT 0,
    -- Lease: which worker currently owns processing, and until when.
    lease_owner             VARCHAR(100),
    lease_expires_at        TIMESTAMPTZ,
    -- Recovery scheduling.
    attempt_count           INT            NOT NULL DEFAULT 0,
    next_attempt_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- Durable idempotency: one transfer per partner per external reference.
    CONSTRAINT uq_transfers_partner_external_ref UNIQUE (partner_id, external_reference)
);

CREATE INDEX ix_transfers_recovery ON transfers (state, next_attempt_at);
CREATE INDEX ix_transfers_reconciliation ON transfers (reconciliation_required) WHERE reconciliation_required;

--changeset transfer-service:003-create-transfer-events
-- Append-only audit log of state transitions and financial operation attempts.
CREATE TABLE transfer_events (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID         NOT NULL REFERENCES transfers (id),
    event_type  VARCHAR(50)  NOT NULL,
    from_state  VARCHAR(32),
    to_state    VARCHAR(32),
    actor       VARCHAR(100) NOT NULL,
    detail      JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_transfer_events_transfer ON transfer_events (transfer_id, id);

--changeset transfer-service:003-create-financial-operations
-- One row per external financial effect (debit, payment, refund).
-- operation_reference is deterministic (e.g. DR-<transfer reference>) and is sent to
-- the external system, so retries and recovery always target the same operation.
CREATE TABLE financial_operations (
    id                  BIGSERIAL PRIMARY KEY,
    transfer_id         UUID           NOT NULL REFERENCES transfers (id),
    operation_type      VARCHAR(20)    NOT NULL,
    operation_reference VARCHAR(60)    NOT NULL UNIQUE,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    status              VARCHAR(20)    NOT NULL,
    external_id         VARCHAR(100),
    attempt_count       INT            NOT NULL DEFAULT 0,
    last_error          TEXT,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- At most one debit, one payment and one refund per transfer.
    CONSTRAINT uq_financial_operations_transfer_type UNIQUE (transfer_id, operation_type)
);
