-- EPIC-004 / STORY-005: admin pharmacy actions (notices, notes, call logs, bulk jobs)
-- Rollback: DROP TABLE IF EXISTS pharmacy_notice, admin_note, pharmacy_call_log, bulk_action_job;
--           DROP TYPE IF EXISTS notice_channel, notice_priority, call_outcome, bulk_action_type, bulk_job_status;

CREATE TYPE notice_channel AS ENUM ('WHATSAPP', 'EMAIL', 'IN_APP');
CREATE TYPE notice_priority AS ENUM ('NORMAL', 'URGENT');
CREATE TYPE call_outcome AS ENUM (
    'RESOLVED', 'FOLLOW_UP_REQUIRED', 'NO_ANSWER', 'CALLBACK_SCHEDULED', 'ESCALATED'
);
CREATE TYPE bulk_action_type AS ENUM ('SUSPEND', 'SEND_NOTICE', 'EXPORT');
CREATE TYPE bulk_job_status AS ENUM ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED');

CREATE TABLE IF NOT EXISTS bulk_action_job (
    id                  UUID PRIMARY KEY,
    action              bulk_action_type NOT NULL,
    payload             JSONB NOT NULL,
    pharmacy_ids        UUID[] NOT NULL,
    status              bulk_job_status NOT NULL DEFAULT 'QUEUED',
    total_pharmacies    INTEGER NOT NULL,
    processed           INTEGER NOT NULL DEFAULT 0,
    succeeded           INTEGER NOT NULL DEFAULT 0,
    failed              INTEGER NOT NULL DEFAULT 0,
    skipped             INTEGER NOT NULL DEFAULT 0,
    skipped_pharmacies  JSONB,
    result_payload      JSONB,
    initiated_by        UUID NOT NULL,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bulk_action_job_status_created
    ON bulk_action_job (status, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE IF NOT EXISTS pharmacy_notice (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    channels        notice_channel[] NOT NULL,
    subject         VARCHAR(200),
    message         TEXT NOT NULL,
    template_name   VARCHAR(100),
    priority        notice_priority NOT NULL DEFAULT 'NORMAL',
    sent_by         UUID NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL,
    bulk_job_id     UUID REFERENCES bulk_action_job (id)
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_notice_pharmacy_sent_at
    ON pharmacy_notice (pharmacy_id, sent_at DESC);

CREATE TABLE IF NOT EXISTS admin_note (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    note            TEXT NOT NULL,
    is_flagged      BOOLEAN NOT NULL DEFAULT FALSE,
    added_by        UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_note_pharmacy_created
    ON admin_note (pharmacy_id, created_at DESC);

CREATE TABLE IF NOT EXISTS pharmacy_call_log (
    id                  UUID PRIMARY KEY,
    pharmacy_id         UUID NOT NULL REFERENCES pharmacies (id),
    duration_seconds    INTEGER NOT NULL CHECK (duration_seconds >= 1),
    call_outcome        call_outcome NOT NULL,
    notes               TEXT,
    logged_by           UUID NOT NULL,
    logged_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_call_log_pharmacy_logged
    ON pharmacy_call_log (pharmacy_id, logged_at DESC);
