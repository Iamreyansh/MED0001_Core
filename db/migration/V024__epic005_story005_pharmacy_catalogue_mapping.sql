-- EPIC-005 / STORY-005: pharmacy_catalogue_mapping + BULK_MAP job type
-- Rollback: DROP TABLE IF EXISTS pharmacy_catalogue_mapping;
--           -- enum value BULK_MAP cannot be removed without recreating bulk_action_type
-- Notes: money as BIGINT paise; UNIQUE(pharmacy_id, master_medicine_id);
--        pause_hidden tracks rows hidden by admin catalogue-pause for restoreAll

ALTER TYPE bulk_action_type ADD VALUE IF NOT EXISTS 'BULK_MAP';

CREATE TABLE pharmacy_catalogue_mapping (
    id                   UUID PRIMARY KEY,
    pharmacy_id          UUID NOT NULL REFERENCES pharmacies (id),
    master_medicine_id   UUID NOT NULL REFERENCES medicine_master (id),
    pharmacy_price_paise BIGINT NOT NULL CHECK (pharmacy_price_paise > 0),
    stock_quantity       INTEGER NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    is_visible           BOOLEAN NOT NULL DEFAULT TRUE,
    pause_hidden         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_catalogue_mapping_pharmacy_medicine
        UNIQUE (pharmacy_id, master_medicine_id)
);

CREATE INDEX idx_pcm_pharmacy_visibility_stock
    ON pharmacy_catalogue_mapping (pharmacy_id, is_visible, stock_quantity, master_medicine_id);

CREATE INDEX idx_pcm_medicine_id
    ON pharmacy_catalogue_mapping (master_medicine_id);
