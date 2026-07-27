-- EPIC-003 / STORY-005: pharmacy profile update
-- Rollback: DROP TABLE IF EXISTS pharmacy_profile_otps;
--           DROP TABLE IF EXISTS profile_change_requests;
--           DROP TABLE IF EXISTS pharmacy_bank_accounts;
--           DROP TABLE IF EXISTS pharmacy_operating_hours;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS tagline, DROP COLUMN IF EXISTS registered_pharmacist_name,
--             DROP COLUMN IF EXISTS is_gst_registered, DROP COLUMN IF EXISTS e_invoicing_enabled,
--             DROP COLUMN IF EXISTS tds_applicable, DROP COLUMN IF EXISTS tcs_applicable,
--             DROP COLUMN IF EXISTS gstin_reverification_pending, DROP COLUMN IF EXISTS pending_phone,
--             DROP COLUMN IF EXISTS pending_email;
-- Notes: logo_url exists from V003. Bank account numbers AES-encrypted at app layer.

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS tagline VARCHAR(200),
    ADD COLUMN IF NOT EXISTS registered_pharmacist_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS is_gst_registered BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS e_invoicing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS tds_applicable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS tcs_applicable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS gstin_reverification_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pending_phone VARCHAR(16),
    ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);

CREATE TABLE IF NOT EXISTS pharmacy_operating_hours (
    id           UUID PRIMARY KEY,
    pharmacy_id  UUID NOT NULL REFERENCES pharmacies (id),
    day_of_week  SMALLINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    open_time    TIME,
    close_time   TIME,
    is_closed    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_operating_hours_day UNIQUE (pharmacy_id, day_of_week)
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_operating_hours_pharmacy
    ON pharmacy_operating_hours (pharmacy_id);

CREATE TABLE IF NOT EXISTS pharmacy_bank_accounts (
    id                       UUID PRIMARY KEY,
    pharmacy_id              UUID NOT NULL REFERENCES pharmacies (id),
    account_holder           VARCHAR(100) NOT NULL,
    bank_name                VARCHAR(100) NOT NULL,
    account_number_encrypted TEXT NOT NULL,
    account_number_last4     CHAR(4) NOT NULL,
    ifsc_code                CHAR(11) NOT NULL,
    account_type             VARCHAR(16) NOT NULL,
    verification_status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    penny_drop_reference     VARCHAR(100),
    verified_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMPTZ,
    CONSTRAINT chk_bank_account_type CHECK (account_type IN ('CURRENT', 'SAVINGS')),
    CONSTRAINT chk_bank_verification_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'FAILED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacy_bank_accounts_active
    ON pharmacy_bank_accounts (pharmacy_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS profile_change_requests (
    id          UUID PRIMARY KEY,
    pharmacy_id UUID NOT NULL REFERENCES pharmacies (id),
    field_name  VARCHAR(100) NOT NULL,
    old_value   TEXT NOT NULL,
    new_value   TEXT NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'PENDING_APPROVAL',
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_profile_change_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_profile_change_requests_pharmacy
    ON profile_change_requests (pharmacy_id);

CREATE TABLE IF NOT EXISTS pharmacy_profile_otps (
    id           UUID PRIMARY KEY,
    pharmacy_id  UUID NOT NULL REFERENCES pharmacies (id),
    channel      VARCHAR(8) NOT NULL,
    target_value VARCHAR(255) NOT NULL,
    otp_hash     VARCHAR(60) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    attempts     SMALLINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_profile_otp_channel CHECK (channel IN ('PHONE', 'EMAIL'))
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_profile_otps_lookup
    ON pharmacy_profile_otps (pharmacy_id, channel);
