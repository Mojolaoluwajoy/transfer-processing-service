--liquibase formatted sql

--changeset transfer-service:007-widen-mock-provider-scenario
-- Scenarios combine several simulation tokens (e.g. FAIL,CONFLICTING_CALLBACKS,SLOW_RESPONSE).
ALTER TABLE mock_provider.payments ALTER COLUMN scenario TYPE VARCHAR(200);
