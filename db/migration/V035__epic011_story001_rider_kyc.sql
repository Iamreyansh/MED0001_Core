-- EPIC-011 / STORY-001: rider onboarding & KYC
-- Rollback: DROP TABLE IF EXISTS rider_kyc_documents;
--           DROP TABLE IF EXISTS riders;
-- Notes: money columns are BIGINT paise; phone unique among active (non-deleted) riders;
--        preferred/primary zone FKs nullable → zones(id)

CREATE TABLE riders (
    id                              UUID PRIMARY KEY,
    name                            VARCHAR(100) NOT NULL,
    phone                           VARCHAR(15) NOT NULL,
    email                           VARCHAR(255),
    vehicle_type                    VARCHAR(16) NOT NULL,
    vehicle_plate_number            VARCHAR(15) NOT NULL,
    primary_zone_id                 UUID REFERENCES zones (id),
    status                          VARCHAR(16) NOT NULL DEFAULT 'PENDING_KYC',
    kyc_status                      VARCHAR(16) NOT NULL DEFAULT 'NOT_SUBMITTED',
    kyc_submitted_at                TIMESTAMPTZ,
    kyc_reviewed_at                 TIMESTAMPTZ,
    kyc_reviewed_by                 UUID,
    kyc_rejection_reason            VARCHAR(100),
    kyc_rejection_notes             TEXT,
    aadhaar_verified                BOOLEAN NOT NULL DEFAULT FALSE,
    avg_rating                      NUMERIC(3, 2),
    total_trips                     INTEGER NOT NULL DEFAULT 0,
    on_time_pct                     NUMERIC(5, 2),
    earnings_wallet_balance_paise   BIGINT NOT NULL DEFAULT 0,
    cod_in_hand_paise               BIGINT NOT NULL DEFAULT 0,
    daily_streak_days               INTEGER NOT NULL DEFAULT 0,
    blocked_reason                  VARCHAR(100),
    blocked_by                      UUID,
    blocked_at                      TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at                      TIMESTAMPTZ,
    CONSTRAINT chk_riders_vehicle_type CHECK (vehicle_type IN ('BIKE', 'BICYCLE', 'SCOOTER')),
    CONSTRAINT chk_riders_status CHECK (status IN ('PENDING_KYC', 'ACTIVE', 'OFFLINE', 'BLOCKED')),
    CONSTRAINT chk_riders_kyc_status CHECK (
        kyc_status IN ('NOT_SUBMITTED', 'SUBMITTED', 'APPROVED', 'REJECTED')
    )
);

CREATE UNIQUE INDEX uq_riders_phone_active
    ON riders (phone)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_riders_status ON riders (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_riders_kyc_status ON riders (kyc_status) WHERE deleted_at IS NULL;
CREATE INDEX idx_riders_created_at ON riders (created_at) WHERE deleted_at IS NULL;

CREATE TABLE rider_kyc_documents (
    id                    UUID PRIMARY KEY,
    rider_id              UUID NOT NULL REFERENCES riders (id),
    document_type         VARCHAR(32) NOT NULL,
    document_number       VARCHAR(50),
    file_key              TEXT NOT NULL,
    file_url              TEXT NOT NULL,
    file_size_bytes       INTEGER NOT NULL,
    mime_type             VARCHAR(50) NOT NULL,
    expiry_date           DATE,
    expiry_alert_sent     BOOLEAN NOT NULL DEFAULT FALSE,
    verification_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    rejection_reason      VARCHAR(255),
    uploaded_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           UUID,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT chk_rider_kyc_doc_type CHECK (
        document_type IN (
            'DRIVING_LICENCE',
            'VEHICLE_RC',
            'VEHICLE_INSURANCE',
            'PUC_CERTIFICATE',
            'AADHAAR',
            'PAN'
        )
    ),
    CONSTRAINT chk_rider_kyc_verification CHECK (
        verification_status IN ('PENDING', 'APPROVED', 'REJECTED')
    )
);

CREATE INDEX idx_rider_kyc_docs_rider
    ON rider_kyc_documents (rider_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_rider_kyc_docs_rider_type
    ON rider_kyc_documents (rider_id, document_type)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_rider_kyc_docs_expiry_alert
    ON rider_kyc_documents (expiry_date)
    WHERE deleted_at IS NULL
      AND expiry_alert_sent = FALSE
      AND document_type IN ('VEHICLE_INSURANCE', 'PUC_CERTIFICATE');
