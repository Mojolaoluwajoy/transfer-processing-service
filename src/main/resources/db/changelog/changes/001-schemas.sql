--liquibase formatted sql

--changeset transfer-service:001-create-mock-schemas
-- The mocks share the database server but own separate schemas, so the service
-- can never read their tables directly: it must go through their HTTP APIs.
CREATE SCHEMA IF NOT EXISTS mock_fineract;
CREATE SCHEMA IF NOT EXISTS mock_provider;
