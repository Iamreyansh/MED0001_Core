-- EPIC-003 / STORY-002: KYC document upload
-- Rollback: DROP TABLE IF EXISTS kyc_expiry_alerts;
--           DROP TABLE IF EXISTS kyc_document_access_audit;
--           DROP TABLE IF EXISTS kyc_documents;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS kyc_submitted_at;

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS kyc_submitted_at TIMESTAMPTZ;

CREATE TABLE kyc_documents (
    id                UUID PRIMARY KEY,
    pharmacy_id       UUID NOT NULL REFERENCES pharmacies (id),
    document_type     VARCHAR(32) NOT NULL,
    file_key          TEXT NOT NULL,
    file_name         VARCHAR(255) NOT NULL,
    file_size_bytes   INTEGER NOT NULL,
    file_mime_type    VARCHAR(50) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'UPLOADED',
    rejection_reason  TEXT,
    expiry_date       DATE,
    verified_by       UUID,
    verified_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ
);

CREATE INDEX idx_kyc_documents_pharmacy ON kyc_documents (pharmacy_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_kyc_documents_pharmacy_type ON kyc_documents (pharmacy_id, document_type) WHERE deleted_at IS NULL;

-- One active (non-REJECTED) doc per (pharmacy, type) at a time
CREATE UNIQUE INDEX uq_kyc_documents_active_type
    ON kyc_documents (pharmacy_id, document_type)
    WHERE deleted_at IS NULL AND status IN ('UPLOADED', 'UNDER_REVIEW', 'VERIFIED');

CREATE TABLE kyc_document_access_audit (
    id          UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES kyc_documents (id),
    pharmacy_id UUID NOT NULL,
    admin_id    UUID NOT NULL,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kyc_audit_document ON kyc_document_access_audit (document_id);
CREATE INDEX idx_kyc_audit_accessed  ON kyc_document_access_audit (accessed_at);

-- ponytail: expiry alerts are scheduled rows; worker (STORY-005+) reads them on a cron and sends notifications
CREATE TABLE kyc_expiry_alerts (
    id          UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES kyc_documents (id),
    pharmacy_id UUID NOT NULL,
    alert_at    TIMESTAMPTZ NOT NULL,
    template    VARCHAR(80) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kyc_expiry_alerts_alert_at ON kyc_expiry_alerts (alert_at);
