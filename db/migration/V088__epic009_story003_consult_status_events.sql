-- EPIC-009 / STORY-003: consult session lifecycle (status audit + session columns)
-- Rollback:
--   DROP INDEX IF EXISTS idx_consult_status_events_consult_created;
--   DROP TABLE IF EXISTS consult_status_events;
--   ALTER TABLE consults DROP COLUMN IF EXISTS clinical_notes;
--   ALTER TABLE consults DROP COLUMN IF EXISTS rated_at;
--   ALTER TABLE consults DROP COLUMN IF EXISTS duration_minutes;
-- Notes: call_started_at/call_ended_at/rating/feedback/is_advice_only already on V087 consults.
--        UUID v4 ids. clinical_notes never exposed on customer APIs.

ALTER TABLE consults
    ADD COLUMN IF NOT EXISTS duration_minutes NUMERIC(5, 2) NULL,
    ADD COLUMN IF NOT EXISTS rated_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS clinical_notes TEXT NULL;

CREATE TABLE consult_status_events (
    id           UUID PRIMARY KEY,
    consult_id   UUID NOT NULL REFERENCES consults (id),
    from_status  VARCHAR(32) NOT NULL,
    to_status    VARCHAR(32) NOT NULL,
    actor_id     UUID NOT NULL,
    notes        VARCHAR(500) NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_consult_status_events_from CHECK (
        from_status IN (
            'REQUESTED',
            'DOCTOR_REVIEWING',
            'CALLING',
            'IN_CALL',
            'COMPLETED',
            'CANCELLED'
        )
    ),
    CONSTRAINT chk_consult_status_events_to CHECK (
        to_status IN (
            'REQUESTED',
            'DOCTOR_REVIEWING',
            'CALLING',
            'IN_CALL',
            'COMPLETED',
            'CANCELLED'
        )
    )
);

CREATE INDEX idx_consult_status_events_consult_created
    ON consult_status_events (consult_id, created_at DESC);
