-- EPIC-008 / STORY-005: prescribing doctor registry and verification
-- Rollback:
--   DROP INDEX IF EXISTS idx_doctor_schedule_event_doctor_created;
--   DROP TABLE IF EXISTS doctor_schedule_event;
--   DROP INDEX IF EXISTS idx_prescription_doctor_link_doctor;
--   DROP TABLE IF EXISTS prescription_doctor_link;
--   DROP INDEX IF EXISTS idx_doctor_status_prescription_count;
--   DROP INDEX IF EXISTS idx_doctor_registration_no;
--   DROP TABLE IF EXISTS doctor;
-- Notes: registration_no UNIQUE (UNKNOWN-{uuid_prefix} when OCR illegible).
--        v1 verify is manual only (NMC_REGISTRY|STATE_BOARD|MANUAL). Blacklist terminal in v1.
--        prescription_doctor_link associates Rx ↔ doctor for card lookup + auto-flags.

CREATE TABLE doctor (
    id                    UUID PRIMARY KEY,
    registration_no       VARCHAR(64) NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    qualification         VARCHAR(32) NULL,
    specialty             VARCHAR(200) NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    source                VARCHAR(20) NOT NULL,
    prescription_count    INTEGER NOT NULL DEFAULT 0,
    scheduled_drug_count  INTEGER NOT NULL DEFAULT 0,
    verification_method   VARCHAR(20) NULL,
    verified_by           UUID NULL,
    verified_at           TIMESTAMPTZ NULL,
    verification_notes    VARCHAR(500) NULL,
    blacklist_reason      TEXT NULL,
    blacklisted_by        UUID NULL,
    blacklisted_at        TIMESTAMPTZ NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMPTZ NULL,
    CONSTRAINT uq_doctor_registration_no UNIQUE (registration_no),
    CONSTRAINT chk_doctor_status CHECK (status IN ('UNVERIFIED', 'VERIFIED', 'BLACKLISTED')),
    CONSTRAINT chk_doctor_source CHECK (source IN ('OCR', 'TELECONSULT', 'MANUAL')),
    CONSTRAINT chk_doctor_qualification CHECK (
        qualification IS NULL OR qualification IN (
            'MBBS', 'MBBS MD', 'MBBS MS', 'BDS', 'BAMS', 'BHMS', 'BUMS', 'MDS', 'MD'
        )
    ),
    CONSTRAINT chk_doctor_verification_method CHECK (
        verification_method IS NULL OR verification_method IN (
            'NMC_REGISTRY', 'STATE_BOARD', 'MANUAL'
        )
    )
);

CREATE INDEX idx_doctor_status_prescription_count
    ON doctor (status, prescription_count DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_doctor_name
    ON doctor (name)
    WHERE deleted_at IS NULL;

CREATE TABLE prescription_doctor_link (
    rx_id                       UUID PRIMARY KEY REFERENCES prescription (id),
    doctor_id                   UUID NOT NULL REFERENCES doctor (id),
    unrecognized_qualification  BOOLEAN NOT NULL DEFAULT FALSE,
    pending_blacklist_flag      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prescription_doctor_link_doctor
    ON prescription_doctor_link (doctor_id);

-- Rolling-window events for scheduled_drug_count soft alert (>50 in 30d).
CREATE TABLE doctor_schedule_event (
    id         UUID PRIMARY KEY,
    doctor_id  UUID NOT NULL REFERENCES doctor (id),
    rx_id      UUID NOT NULL REFERENCES prescription (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doctor_schedule_event_doctor_created
    ON doctor_schedule_event (doctor_id, created_at DESC);
