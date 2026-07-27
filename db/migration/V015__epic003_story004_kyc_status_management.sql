-- EPIC-003 / STORY-004: KYC status management (admin)
-- Rollback: DROP TABLE IF EXISTS audit_log;
--           DROP TABLE IF EXISTS zones;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS code, DROP COLUMN IF EXISTS rejection_reason,
--             DROP COLUMN IF EXISTS rejection_details, DROP COLUMN IF EXISTS activated_at,
--             DROP COLUMN IF EXISTS suspended_at, DROP COLUMN IF EXISTS suspend_type,
--             DROP COLUMN IF EXISTS kyc_sla_reset_at;
--           DROP SEQUENCE IF EXISTS pharmacy_code_seq;

CREATE SEQUENCE IF NOT EXISTS pharmacy_code_seq START 1;

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS code VARCHAR(16),
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(200),
    ADD COLUMN IF NOT EXISTS rejection_details VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS activated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspend_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS kyc_sla_reset_at TIMESTAMPTZ;

UPDATE pharmacies
SET code = 'PHM-' || lpad(nextval('pharmacy_code_seq')::text, 4, '0')
WHERE code IS NULL;

ALTER TABLE pharmacies
    ALTER COLUMN code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacies_code ON pharmacies (code);

-- ponytail: stub zones until EPIC-009 / EPIC-004 zone service
CREATE TABLE IF NOT EXISTS zones (
    id     UUID PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO zones (id, name, active) VALUES
    ('a0000001-0000-4000-8000-000000000001', 'Koramangala Zone', TRUE),
    ('a0000002-0000-4000-8000-000000000002', 'Mumbai South Zone', TRUE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id   UUID NOT NULL,
    action      VARCHAR(100) NOT NULL,
    actor_id    UUID,
    actor_role  VARCHAR(50) NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address  INET,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log (created_at);
