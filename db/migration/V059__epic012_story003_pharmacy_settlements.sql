-- EPIC-012 / STORY-003: pharmacy settlements finance façade
-- Rollback:
--   ALTER TABLE settlement DROP CONSTRAINT IF EXISTS chk_settlement_status;
--   ALTER TABLE settlement ADD CONSTRAINT chk_settlement_status CHECK (
--       status IN ('PENDING_RELEASE', 'RELEASED', 'PAID', 'HELD', 'FAILED'));
--   ALTER TABLE settlement
--       DROP COLUMN IF EXISTS held_by,
--       DROP COLUMN IF EXISTS held_at,
--       DROP COLUMN IF EXISTS notes,
--       DROP COLUMN IF EXISTS orders_count,
--       DROP COLUMN IF EXISTS gst_on_commission_paise,
--       DROP COLUMN IF EXISTS carry_forward_consumed_at;
-- Notes: reuses V019 settlement (paise BIGINT). Line items derived from orders at read time
--        (DELIVERED + CAPTURED/COLLECTED_COD) — no settlement_line_item table.
--        PENDING_RELEASE remains storage status; finance API normalises to PENDING.

ALTER TABLE settlement DROP CONSTRAINT IF EXISTS chk_settlement_status;

ALTER TABLE settlement ADD CONSTRAINT chk_settlement_status CHECK (
    status IN (
        'PENDING_RELEASE',
        'RELEASED',
        'PAID',
        'HELD',
        'FAILED',
        'BELOW_THRESHOLD_CARRIED'
    )
);

ALTER TABLE settlement
    ADD COLUMN IF NOT EXISTS held_by UUID,
    ADD COLUMN IF NOT EXISTS held_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS orders_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gst_on_commission_paise BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carry_forward_consumed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_settlement_status_period
    ON settlement (status, period_start DESC)
    WHERE deleted_at IS NULL;
