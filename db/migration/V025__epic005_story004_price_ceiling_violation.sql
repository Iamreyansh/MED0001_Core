-- EPIC-005 / STORY-004: price_ceiling_violation + medicine ceiling metadata
-- Rollback: DROP TABLE IF EXISTS price_ceiling_violation;
--           ALTER TABLE medicine_master
--             DROP COLUMN IF EXISTS mrp_ceiling_effective_from,
--             DROP COLUMN IF EXISTS mrp_ceiling_reason,
--             DROP COLUMN IF EXISTS mrp_ceiling_set_by,
--             DROP COLUMN IF EXISTS mrp_ceiling_set_by_name,
--             DROP COLUMN IF EXISTS mrp_ceiling_set_by_role,
--             DROP COLUMN IF EXISTS mrp_ceiling_set_at;
-- Notes: money as BIGINT paise; unique (medicine_id, pharmacy_id) for nightly UPSERT

ALTER TABLE medicine_master
    ADD COLUMN IF NOT EXISTS mrp_ceiling_effective_from DATE,
    ADD COLUMN IF NOT EXISTS mrp_ceiling_reason TEXT,
    ADD COLUMN IF NOT EXISTS mrp_ceiling_set_by UUID,
    ADD COLUMN IF NOT EXISTS mrp_ceiling_set_by_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS mrp_ceiling_set_by_role VARCHAR(64),
    ADD COLUMN IF NOT EXISTS mrp_ceiling_set_at TIMESTAMPTZ;

CREATE TABLE price_ceiling_violation (
    id                     UUID PRIMARY KEY,
    medicine_id            UUID NOT NULL REFERENCES medicine_master (id),
    pharmacy_id            UUID NOT NULL REFERENCES pharmacies (id),
    ceiling_price_paise    BIGINT NOT NULL CHECK (ceiling_price_paise > 0),
    pharmacy_price_paise   BIGINT NOT NULL CHECK (pharmacy_price_paise > 0),
    overage_amount_paise   BIGINT NOT NULL CHECK (overage_amount_paise >= 0),
    status                 VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'NOTIFIED', 'RESOLVED')),
    detected_at            TIMESTAMPTZ NOT NULL,
    last_notified_at       TIMESTAMPTZ,
    resolved_at            TIMESTAMPTZ,
    CONSTRAINT uq_price_ceiling_violation_medicine_pharmacy
        UNIQUE (medicine_id, pharmacy_id)
);

CREATE INDEX idx_pcv_status_medicine
    ON price_ceiling_violation (status, medicine_id);

CREATE INDEX idx_pcv_pharmacy
    ON price_ceiling_violation (pharmacy_id);
