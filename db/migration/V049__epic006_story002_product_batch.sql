-- EPIC-006 / STORY-002: product_batch, batch_adjustment_log, inventory_stock_movement
-- Rollback: DROP TABLE IF EXISTS inventory_stock_movement;
--           DROP TABLE IF EXISTS batch_adjustment_log;
--           DROP TABLE IF EXISTS product_batch;
-- Notes: money as BIGINT paise. batch_adjustment_log and inventory_stock_movement are append-only
--        (no soft delete). Duplicate (pharmacy_id, product_id, batch_number) tops up qty in app.
--        grn_item_id reserved for STORY-004 (no FK yet).

CREATE TABLE product_batch (
    id                      UUID PRIMARY KEY,
    product_id              UUID NOT NULL REFERENCES pharmacy_product (id),
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    batch_number            VARCHAR(50) NOT NULL,
    expiry_date             DATE NOT NULL,
    manufactured_date       DATE,
    quantity_received       INTEGER NOT NULL CHECK (quantity_received > 0),
    quantity_current        INTEGER NOT NULL CHECK (quantity_current >= 0),
    purchase_price_paise    BIGINT NOT NULL CHECK (purchase_price_paise > 0),
    mrp_paise               BIGINT NOT NULL CHECK (mrp_paise > 0),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    write_off_reason        VARCHAR(32),
    write_off_notes         TEXT,
    grn_item_id             UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_batch_pharmacy_product_number
        UNIQUE (pharmacy_id, product_id, batch_number),
    CONSTRAINT chk_product_batch_write_off_reason CHECK (
        write_off_reason IS NULL
        OR write_off_reason IN ('EXPIRED', 'DAMAGED', 'REGULATORY')
    )
);

CREATE INDEX idx_product_batch_pharmacy_product
    ON product_batch (pharmacy_id, product_id);

CREATE INDEX idx_product_batch_pharmacy_expiry_active
    ON product_batch (pharmacy_id, expiry_date)
    WHERE is_active = TRUE AND quantity_current > 0;

CREATE TABLE batch_adjustment_log (
    id              UUID PRIMARY KEY,
    batch_id        UUID NOT NULL REFERENCES product_batch (id),
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    staff_id        UUID NOT NULL REFERENCES pharmacy_staff (id),
    adjustment      INTEGER NOT NULL,
    reason          VARCHAR(32) NOT NULL,
    before_qty      INTEGER NOT NULL,
    after_qty       INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_batch_adjustment_reason CHECK (
        reason IN ('DAMAGE', 'RETURN', 'AUDIT_CORRECTION', 'EXPIRY_WRITE_OFF')
    )
);

CREATE INDEX idx_batch_adjustment_log_batch
    ON batch_adjustment_log (batch_id, created_at DESC);

CREATE TABLE inventory_stock_movement (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    product_id      UUID NOT NULL REFERENCES pharmacy_product (id),
    batch_id        UUID REFERENCES product_batch (id),
    movement_type   VARCHAR(32) NOT NULL,
    quantity_delta  INTEGER NOT NULL,
    reason          VARCHAR(64),
    staff_id        UUID REFERENCES pharmacy_staff (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_inventory_stock_movement_type CHECK (
        movement_type IN ('RECEIPT', 'ADJUSTMENT', 'WRITE_OFF', 'SALE')
    )
);

CREATE INDEX idx_inventory_stock_movement_product
    ON inventory_stock_movement (pharmacy_id, product_id, created_at DESC);
