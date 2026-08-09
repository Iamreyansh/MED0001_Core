-- EPIC-006 / STORY-001: pharmacy_product stock master
-- Rollback: DELETE FROM feature_flags WHERE name IN
--             ('inventory_online_visibility', 'inventory_distributors', 'inventory_reorder');
--           DROP TABLE IF EXISTS pharmacy_product;
-- Notes: money as BIGINT paise (mrp_paise, cost_value_paise). Denormalized stock/expiry/movement
--        columns are stubbed until STORY-002 (batches) and POS; recalculated on batch mutation later.
--        Plan enforcement for is_online_visible uses medmate.inventory.growth-features-enabled
--        (InventoryPlanPort); feature_flags rows below are documentation/ops seeds only.

CREATE TABLE pharmacy_product (
    id                        UUID PRIMARY KEY,
    pharmacy_id               UUID NOT NULL REFERENCES pharmacies (id),
    master_medicine_id        UUID REFERENCES medicine_master (id),
    name                      VARCHAR(200) NOT NULL,
    salt_composition          VARCHAR(500),
    manufacturer              VARCHAR(200),
    pack_size                 INTEGER NOT NULL CHECK (pack_size > 0),
    pack_unit                 VARCHAR(50) NOT NULL,
    category_id               UUID REFERENCES medicine_category (id),
    form                      VARCHAR(32) NOT NULL,
    schedule                  VARCHAR(16) NOT NULL DEFAULT 'OTC',
    hsn_code                  VARCHAR(8),
    gst_pct                   SMALLINT NOT NULL DEFAULT 12,
    mrp_paise                 BIGINT NOT NULL CHECK (mrp_paise >= 0),
    is_rx_only                BOOLEAN NOT NULL DEFAULT FALSE,
    is_loose_selling_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    is_online_visible         BOOLEAN NOT NULL DEFAULT FALSE,
    reorder_level             INTEGER NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    rack_locations            TEXT[],
    total_stock_units         INTEGER NOT NULL DEFAULT 0 CHECK (total_stock_units >= 0),
    total_batches             INTEGER NOT NULL DEFAULT 0 CHECK (total_batches >= 0),
    earliest_expiry           DATE,
    cost_value_paise          BIGINT NOT NULL DEFAULT 0 CHECK (cost_value_paise >= 0),
    last_movement_at          TIMESTAMPTZ,
    product_photo_url         TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at                TIMESTAMPTZ,
    CONSTRAINT chk_pharmacy_product_form CHECK (form IN (
        'TABLET', 'SYRUP', 'CAPSULE', 'DROPS', 'INJECTION', 'POWDER', 'CREAM', 'GEL', 'OTHER'
    )),
    CONSTRAINT chk_pharmacy_product_schedule CHECK (schedule IN (
        'OTC', 'H', 'H1', 'X', 'G', 'OTHER'
    )),
    CONSTRAINT chk_pharmacy_product_gst CHECK (gst_pct IN (0, 5, 12, 18, 28))
);

CREATE INDEX idx_pharmacy_product_pharmacy
    ON pharmacy_product (pharmacy_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pharmacy_product_pharmacy_name
    ON pharmacy_product (pharmacy_id, name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pharmacy_product_pharmacy_stock
    ON pharmacy_product (pharmacy_id, total_stock_units)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pharmacy_product_pharmacy_expiry
    ON pharmacy_product (pharmacy_id, earliest_expiry)
    WHERE deleted_at IS NULL;

-- Ops documentation seeds (enforcement is via InventoryPlanPort / growth-features-enabled).
INSERT INTO feature_flags (id, name, description, environment, enabled, rollout_percentage, notes, updated_at)
VALUES
    ('b6000001-0000-4000-8000-000000000001', 'inventory_online_visibility',
     'Allows pharmacies to mark products visible on the customer online store',
     'production', FALSE, 0, 'Growth+ plan; Free plan locked via growth-features-enabled', NOW()),
    ('b6000001-0000-4000-8000-000000000002', 'inventory_distributors',
     'Enables distributor / supplier management in inventory',
     'production', FALSE, 0, 'Future STORY', NOW()),
    ('b6000001-0000-4000-8000-000000000003', 'inventory_reorder',
     'Enables reorder suggestions and automated PO drafts',
     'production', FALSE, 0, 'Future STORY', NOW()),
    ('b6000001-0000-4000-8000-000000000011', 'inventory_online_visibility',
     'Allows pharmacies to mark products visible on the customer online store',
     'staging', TRUE, 100, NULL, NOW()),
    ('b6000001-0000-4000-8000-000000000012', 'inventory_distributors',
     'Enables distributor / supplier management in inventory',
     'staging', FALSE, 0, NULL, NOW()),
    ('b6000001-0000-4000-8000-000000000013', 'inventory_reorder',
     'Enables reorder suggestions and automated PO drafts',
     'staging', FALSE, 0, NULL, NOW()),
    ('b6000001-0000-4000-8000-000000000021', 'inventory_online_visibility',
     'Allows pharmacies to mark products visible on the customer online store',
     'development', TRUE, 100, NULL, NOW()),
    ('b6000001-0000-4000-8000-000000000022', 'inventory_distributors',
     'Enables distributor / supplier management in inventory',
     'development', TRUE, 100, NULL, NOW()),
    ('b6000001-0000-4000-8000-000000000023', 'inventory_reorder',
     'Enables reorder suggestions and automated PO drafts',
     'development', TRUE, 100, NULL, NOW());
