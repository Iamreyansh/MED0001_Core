-- EPIC-003 / STORY-001: pharmacy registration
-- Rollback: DROP TABLE IF EXISTS pharmacy_registration_audit;
--           DROP TABLE IF EXISTS pharmacy_email_otps;
--           DROP TABLE IF EXISTS pincode_reference;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS owner_name, DROP COLUMN IF EXISTS business_name,
--             DROP COLUMN IF EXISTS phone, DROP COLUMN IF EXISTS email, DROP COLUMN IF EXISTS password_hash,
--             DROP COLUMN IF EXISTS business_type, DROP COLUMN IF EXISTS address, DROP COLUMN IF EXISTS status,
--             DROP COLUMN IF EXISTS plan, DROP COLUMN IF EXISTS plan_expires_at, DROP COLUMN IF EXISTS gstin,
--             DROP COLUMN IF EXISTS drug_licence_number, DROP COLUMN IF EXISTS licence_state_code,
--             DROP COLUMN IF EXISTS fssai_number, DROP COLUMN IF EXISTS pan_number,
--             DROP COLUMN IF EXISTS commission_pct, DROP COLUMN IF EXISTS zone_id, DROP COLUMN IF EXISTS is_online,
--             DROP COLUMN IF EXISTS email_verified, DROP COLUMN IF EXISTS can_reapply;
-- Notes: Widens auth stub pharmacies for self-service registration. New compliance columns nullable so
--        existing staff-auth fixtures keep working; registration path always populates them.
--        Owner account lives in pharmacy_staff + assignment to system role owner (V005).
--        ponytail: pincode_reference seeds Karnataka/serviceable samples only; expand to full India later.

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS owner_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS business_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(15),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS password_hash TEXT,
    ADD COLUMN IF NOT EXISTS business_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS address JSONB,
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS plan VARCHAR(32) NOT NULL DEFAULT 'FREE',
    ADD COLUMN IF NOT EXISTS plan_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS gstin VARCHAR(15),
    ADD COLUMN IF NOT EXISTS drug_licence_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS licence_state_code CHAR(2),
    ADD COLUMN IF NOT EXISTS fssai_number VARCHAR(14),
    ADD COLUMN IF NOT EXISTS pan_number VARCHAR(10),
    ADD COLUMN IF NOT EXISTS commission_pct NUMERIC(5, 2) NOT NULL DEFAULT 8.00,
    ADD COLUMN IF NOT EXISTS zone_id UUID,
    ADD COLUMN IF NOT EXISTS is_online BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS can_reapply BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill display fields for stub rows created by auth ITs
UPDATE pharmacies
SET business_name = COALESCE(business_name, name),
    plan = COALESCE(NULLIF(plan, ''), subscription_plan, 'FREE'),
    status = COALESCE(NULLIF(status, ''), 'ACTIVE'),
    email_verified = TRUE
WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacies_gstin
    ON pharmacies (gstin) WHERE gstin IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacies_pan
    ON pharmacies (pan_number) WHERE pan_number IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacies_email
    ON pharmacies (email) WHERE email IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacies_phone
    ON pharmacies (phone) WHERE phone IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacies_drug_licence_state
    ON pharmacies (drug_licence_number, licence_state_code)
    WHERE drug_licence_number IS NOT NULL AND licence_state_code IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE pincode_reference (
    pincode     CHAR(6) PRIMARY KEY,
    state_code  CHAR(2) NOT NULL,
    state_name  VARCHAR(100) NOT NULL,
    serviceable BOOLEAN NOT NULL DEFAULT TRUE
);

-- ponytail: sample serviceable pincodes (Bengaluru + a few metros) — full India seed later
INSERT INTO pincode_reference (pincode, state_code, state_name, serviceable) VALUES
    ('560001', '29', 'Karnataka', TRUE),
    ('560002', '29', 'Karnataka', TRUE),
    ('560003', '29', 'Karnataka', TRUE),
    ('560004', '29', 'Karnataka', TRUE),
    ('560005', '29', 'Karnataka', TRUE),
    ('560008', '29', 'Karnataka', TRUE),
    ('560010', '29', 'Karnataka', TRUE),
    ('560025', '29', 'Karnataka', TRUE),
    ('560034', '29', 'Karnataka', TRUE),
    ('560038', '29', 'Karnataka', TRUE),
    ('560066', '29', 'Karnataka', TRUE),
    ('560068', '29', 'Karnataka', TRUE),
    ('560076', '29', 'Karnataka', TRUE),
    ('560078', '29', 'Karnataka', TRUE),
    ('560095', '29', 'Karnataka', TRUE),
    ('560100', '29', 'Karnataka', TRUE),
    ('560102', '29', 'Karnataka', TRUE),
    ('560103', '29', 'Karnataka', TRUE),
    ('400001', '27', 'Maharashtra', TRUE),
    ('400051', '27', 'Maharashtra', TRUE),
    ('110001', '07', 'Delhi', TRUE),
    ('600001', '33', 'Tamil Nadu', TRUE),
    ('500001', '36', 'Telangana', TRUE),
    ('700001', '19', 'West Bengal', TRUE),
    ('380001', '24', 'Gujarat', TRUE),
    ('302001', '08', 'Rajasthan', TRUE),
    ('226001', '09', 'Uttar Pradesh', TRUE),
    ('999999', '29', 'Karnataka', FALSE);

CREATE TABLE pharmacy_email_otps (
    id            UUID PRIMARY KEY,
    pharmacy_id   UUID NOT NULL REFERENCES pharmacies (id),
    email         VARCHAR(255) NOT NULL,
    otp_hash      VARCHAR(60) NOT NULL,
    attempts      SMALLINT NOT NULL DEFAULT 0,
    resend_count  SMALLINT NOT NULL DEFAULT 0,
    expires_at    TIMESTAMPTZ NOT NULL,
    verified_at   TIMESTAMPTZ,
    locked_at     TIMESTAMPTZ,
    last_sent_at  TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pharmacy_email_otps_email ON pharmacy_email_otps (email);
CREATE INDEX idx_pharmacy_email_otps_pharmacy ON pharmacy_email_otps (pharmacy_id);

CREATE TABLE pharmacy_registration_audit (
    id          UUID PRIMARY KEY,
    pharmacy_id UUID,
    email       VARCHAR(255),
    phone       VARCHAR(15),
    ip_address  INET,
    outcome     VARCHAR(32) NOT NULL,
    error_code  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pharmacy_registration_audit_created ON pharmacy_registration_audit (created_at);
