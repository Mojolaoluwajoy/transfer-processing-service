--liquibase formatted sql

--changeset transfer-service:004-create-outbox
-- Transactional outbox: written in the same transaction as the business change,
-- then published to RabbitMQ by a relay. Unpublished rows survive broker outages.
CREATE TABLE outbox_messages (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_id    UUID         NOT NULL,
    message_type    VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished ON outbox_messages (next_attempt_at, id) WHERE published_at IS NULL;

--changeset transfer-service:004-create-provider-callbacks
-- Every callback is stored as received before it is applied, so duplicates are
-- detected by provider_event_id and conflicting outcomes are never lost.
CREATE TABLE provider_callbacks (
    id                BIGSERIAL PRIMARY KEY,
    provider_event_id VARCHAR(100) NOT NULL UNIQUE,
    payment_reference VARCHAR(60)  NOT NULL,
    reported_status   VARCHAR(20)  NOT NULL,
    payload           JSONB        NOT NULL,
    outcome           VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ
);

CREATE INDEX ix_provider_callbacks_payment_ref ON provider_callbacks (payment_reference);
