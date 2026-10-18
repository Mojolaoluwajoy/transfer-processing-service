--liquibase formatted sql

--changeset transfer-service:006-create-mock-provider
CREATE TABLE mock_provider.payments (
    payment_reference     VARCHAR(60) PRIMARY KEY,
    provider_payment_id   VARCHAR(60)    NOT NULL UNIQUE,
    request_hash          CHAR(64)       NOT NULL,
    destination_account   VARCHAR(20)    NOT NULL,
    destination_bank_code VARCHAR(10)    NOT NULL,
    amount                NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency              CHAR(3)        NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    failure_reason        VARCHAR(100),
    -- Simulation plan chosen at submission (e.g. SUCCEED, FAIL, DUPLICATE_CALLBACKS).
    scenario              VARCHAR(40)    NOT NULL DEFAULT 'SUCCEED',
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Outgoing callbacks are persisted so delayed ones still fire after a restart.
CREATE TABLE mock_provider.callbacks (
    id                BIGSERIAL PRIMARY KEY,
    event_id          VARCHAR(100) NOT NULL,
    payment_reference VARCHAR(60)  NOT NULL REFERENCES mock_provider.payments (payment_reference),
    status            VARCHAR(20)  NOT NULL,
    deliver_at        TIMESTAMPTZ  NOT NULL,
    delivered_at      TIMESTAMPTZ,
    attempt_count     INT          NOT NULL DEFAULT 0,
    last_error        TEXT
);

CREATE INDEX ix_mock_provider_callbacks_due ON mock_provider.callbacks (deliver_at) WHERE delivered_at IS NULL;
