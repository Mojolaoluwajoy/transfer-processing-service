--liquibase formatted sql

--changeset transfer-service:002-create-partners
CREATE TABLE partners (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Only a SHA-256 hash of each key is stored; the raw key is shown once at issue time.
CREATE TABLE partner_api_keys (
    id         UUID PRIMARY KEY,
    partner_id UUID        NOT NULL REFERENCES partners (id),
    key_hash   CHAR(64)    NOT NULL UNIQUE,
    key_prefix VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

-- Source accounts a partner is authorised to debit.
CREATE TABLE partner_accounts (
    partner_id     UUID        NOT NULL REFERENCES partners (id),
    account_number VARCHAR(20) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id, account_number)
);
