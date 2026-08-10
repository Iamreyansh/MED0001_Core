-- EPIC-009 / STORY-001: teleconsult_doctors (in-house teleconsult roster)
-- Rollback:
--   DROP INDEX IF EXISTS idx_teleconsult_doctors_specialty;
--   DROP INDEX IF EXISTS idx_teleconsult_doctors_available;
--   DROP INDEX IF EXISTS uq_teleconsult_doctors_registration_no;
--   DROP TABLE IF EXISTS teleconsult_doctors;
-- Notes: Separate from EPIC-008 `doctor` registry. internal_phone is AES-GCM ciphertext (TEXT).
--        languages_spoken stored as JSONB string array. consults_today reset at midnight IST by job.
--        registration_no unique among non-deleted rows only.

CREATE TABLE teleconsult_doctors (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    qualification      VARCHAR(32) NOT NULL,
    registration_no    VARCHAR(64) NOT NULL,
    specialty          VARCHAR(100) NOT NULL,
    languages_spoken   JSONB NOT NULL,
    years_experience   INTEGER NOT NULL,
    avatar_url         TEXT NOT NULL,
    bio                VARCHAR(500) NOT NULL,
    internal_phone     TEXT NOT NULL,
    is_available       BOOLEAN NOT NULL DEFAULT FALSE,
    avg_rating         NUMERIC(3, 2) NULL,
    total_consults     INTEGER NOT NULL DEFAULT 0,
    consults_today     INTEGER NOT NULL DEFAULT 0,
    last_assigned_at   TIMESTAMPTZ NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ NULL,
    CONSTRAINT chk_teleconsult_doctors_qualification CHECK (
        qualification IN (
            'MBBS', 'MBBS MD', 'MBBS MS', 'BDS', 'BAMS', 'BHMS', 'BUMS'
        )
    ),
    CONSTRAINT chk_teleconsult_doctors_years_experience CHECK (years_experience > 0),
    CONSTRAINT chk_teleconsult_doctors_total_consults CHECK (total_consults >= 0),
    CONSTRAINT chk_teleconsult_doctors_consults_today CHECK (consults_today >= 0),
    CONSTRAINT chk_teleconsult_doctors_avg_rating CHECK (
        avg_rating IS NULL OR (avg_rating >= 1.00 AND avg_rating <= 5.00)
    )
);

CREATE UNIQUE INDEX uq_teleconsult_doctors_registration_no
    ON teleconsult_doctors (registration_no)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_teleconsult_doctors_available
    ON teleconsult_doctors (is_available, last_assigned_at ASC NULLS FIRST)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_teleconsult_doctors_specialty
    ON teleconsult_doctors (specialty)
    WHERE deleted_at IS NULL;
