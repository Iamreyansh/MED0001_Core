-- EPIC-008 / STORY-001: prescription upload and storage
-- Rollback: DROP INDEX IF EXISTS idx_prescription_status;
--           DROP INDEX IF EXISTS idx_prescription_expires;
--           DROP INDEX IF EXISTS idx_prescription_customer_created;
--           DROP TABLE IF EXISTS prescription;
-- Notes: s3_key never exposed via API; file_url is virtual (presigned on read).
--        Soft delete via deleted_at. medicines_extracted JSONB OCR stub payload.

CREATE TABLE prescription (
    id                   UUID PRIMARY KEY,
    customer_id          UUID NOT NULL REFERENCES customers (id),
    type                 VARCHAR(20) NOT NULL,
    status               VARCHAR(30) NOT NULL,
    s3_key               VARCHAR(512) NOT NULL,
    file_size_bytes      BIGINT NOT NULL,
    mime_type            VARCHAR(64) NOT NULL,
    patient_name         VARCHAR(200) NULL,
    notes                VARCHAR(500) NULL,
    doctor_name          VARCHAR(200) NULL,
    prescription_date    DATE NULL,
    source               VARCHAR(20) NOT NULL,
    medicines_extracted  JSONB NULL,
    associated_order_id  UUID NULL,
    teleconsult_id       UUID NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    rejection_reason     VARCHAR(500) NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMPTZ NULL,
    CONSTRAINT uq_prescription_s3_key UNIQUE (s3_key),
    CONSTRAINT chk_prescription_type CHECK (type IN ('UPLOADED', 'E_PRESCRIPTION')),
    CONSTRAINT chk_prescription_status CHECK (status IN (
        'UPLOADED',
        'PENDING_VERIFICATION',
        'VERIFIED',
        'REJECTED',
        'DISPENSED',
        'EXPIRED'
    )),
    CONSTRAINT chk_prescription_source CHECK (source IN ('UPLOAD', 'TELECONSULT')),
    CONSTRAINT chk_prescription_mime CHECK (mime_type IN (
        'application/pdf',
        'image/jpeg',
        'image/png'
    ))
);

CREATE INDEX idx_prescription_customer_created
    ON prescription (customer_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_prescription_expires
    ON prescription (expires_at)
    WHERE deleted_at IS NULL AND status NOT IN ('EXPIRED', 'DISPENSED');

CREATE INDEX idx_prescription_status
    ON prescription (status)
    WHERE deleted_at IS NULL;
