-- EPIC-010 / STORY-008: admin order oversight (disputes, notes, export jobs)
-- Rollback: DROP TABLE IF EXISTS admin_order_export_job;
--           DROP TABLE IF EXISTS order_note;
--           DROP TABLE IF EXISTS order_dispute;
-- Notes: notes append-only (no deleted_at); money stays on orders (paise); commission display-only.

CREATE TABLE order_dispute (
    id               UUID PRIMARY KEY,
    order_id         UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    reason           VARCHAR(500) NOT NULL,
    liable_party     VARCHAR(20) NOT NULL,
    flagged_by       UUID NOT NULL,
    flagged_at       TIMESTAMPTZ NOT NULL,
    resolved         BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at      TIMESTAMPTZ NULL,
    resolution_notes VARCHAR(1000) NULL,
    CONSTRAINT chk_order_dispute_liable
        CHECK (liable_party IN ('PHARMACY', 'RIDER', 'PLATFORM', 'CUSTOMER'))
);

CREATE UNIQUE INDEX uq_order_dispute_open_order
    ON order_dispute (order_id)
    WHERE resolved = FALSE;

CREATE INDEX idx_order_dispute_order
    ON order_dispute (order_id, flagged_at DESC);

CREATE TABLE order_note (
    id         UUID PRIMARY KEY,
    order_id   UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    note       VARCHAR(2000) NOT NULL,
    is_pinned  BOOLEAN NOT NULL DEFAULT FALSE,
    added_by   UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_note_order_pinned
    ON order_note (order_id, is_pinned DESC, created_at DESC);

CREATE TABLE admin_order_export_job (
    id           UUID PRIMARY KEY,
    requested_by UUID NOT NULL,
    filters      JSONB NOT NULL,
    row_count    INTEGER NULL,
    status       VARCHAR(20) NOT NULL,
    s3_key       VARCHAR(512) NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ NULL,
    CONSTRAINT chk_admin_order_export_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED'))
);

CREATE INDEX idx_admin_order_export_job_requester
    ON admin_order_export_job (requested_by, created_at DESC);

CREATE INDEX idx_admin_order_export_job_pending
    ON admin_order_export_job (status, created_at ASC)
    WHERE status IN ('PENDING', 'PROCESSING');
