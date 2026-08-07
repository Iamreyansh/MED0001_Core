-- EPIC-005 / STORY-001: medicine_ban_job (storefront hide fan-out tracking)
-- Rollback: DROP TABLE IF EXISTS medicine_ban_job;

CREATE TABLE medicine_ban_job (
    id              UUID PRIMARY KEY,
    medicine_id     UUID NOT NULL REFERENCES medicine_master (id),
    status          VARCHAR(16) NOT NULL,
    mappings_hidden INTEGER NOT NULL DEFAULT 0,
    reason          TEXT,
    initiated_by    UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_medicine_ban_job_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_medicine_ban_job_medicine ON medicine_ban_job (medicine_id);
CREATE INDEX idx_medicine_ban_job_status ON medicine_ban_job (status)
    WHERE status IN ('QUEUED', 'RUNNING');
