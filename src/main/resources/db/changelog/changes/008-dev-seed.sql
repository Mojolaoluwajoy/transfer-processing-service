--liquibase formatted sql

--changeset transfer-service:008-dev-seed context:dev
-- Demo data, applied only with the dev profile (spring.liquibase.contexts includes "dev").
-- Raw keys, for local use only: dev-partner-key-001 (Demo Merchant), dev-partner-key-002 (Other Merchant).
INSERT INTO partners (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Demo Merchant'),
    ('00000000-0000-0000-0000-000000000002', 'Other Merchant');

INSERT INTO partner_api_keys (id, partner_id, key_hash, key_prefix) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
     'c2bdb87dd186694d736d32dca74c0c4cd55e33fd245a3ad500860e0cc9bf3a2f', 'dev-partner'),
    ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000002',
     '5eef1354d8f8af81c65f05ca4bfb02d5974011bbab68a7ffcac6990a7368ca38', 'dev-partner');

INSERT INTO partner_accounts (partner_id, account_number) VALUES
    ('00000000-0000-0000-0000-000000000001', '1001610327'),
    ('00000000-0000-0000-0000-000000000002', '1001610328');

INSERT INTO mock_fineract.accounts (account_number, available_balance) VALUES
    ('1001610327', 1000000.00),
    ('1001610328', 1000000.00);
