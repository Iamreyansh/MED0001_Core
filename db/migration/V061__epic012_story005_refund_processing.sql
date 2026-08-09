-- EPIC-012 / STORY-005: refund processing finance queue
-- Rollback:
--   DROP INDEX IF EXISTS idx_refund_status_created;
--   ALTER TABLE refund DROP COLUMN IF EXISTS completed_at;
--   ALTER TABLE refund DROP COLUMN IF EXISTS expected_by;
--   ALTER TABLE refund DROP COLUMN IF EXISTS processed_by;
--   ALTER TABLE refund DROP COLUMN IF EXISTS auto_processed;
--   ALTER TABLE refund DROP CONSTRAINT IF EXISTS chk_refund_status;
--   ALTER TABLE refund ADD CONSTRAINT chk_refund_status
--       CHECK (status IN ('INITIATED', 'PROCESSED', 'FAILED'));
-- Notes: Reuses V032 refund. API PENDING/PROCESSING/COMPLETED map to PENDING/INITIATED/PROCESSED.
--        Money remains BIGINT paise. is_overdue is query-time only.

ALTER TABLE refund DROP CONSTRAINT IF EXISTS chk_refund_status;
ALTER TABLE refund ADD CONSTRAINT chk_refund_status
    CHECK (status IN ('PENDING', 'INITIATED', 'PROCESSED', 'FAILED'));

ALTER TABLE refund
    ADD COLUMN IF NOT EXISTS auto_processed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS processed_by UUID NULL,
    ADD COLUMN IF NOT EXISTS expected_by DATE NULL,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_refund_status_created
    ON refund (status, created_at DESC);
