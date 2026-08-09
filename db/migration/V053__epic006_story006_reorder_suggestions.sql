-- EPIC-006 / STORY-006: reorder_suggestion_snapshot + purchase_order + purchase_order_item
-- Rollback: DROP TABLE IF EXISTS purchase_order_item;
--           DROP TABLE IF EXISTS purchase_order;
--           DROP TABLE IF EXISTS reorder_suggestion_snapshot;
-- Notes: money as BIGINT paise. Nightly refresh replaces rows for (pharmacy_id, snapshot_date).
--        PO number format PO-YYYY-MM-NNNNNN (per-pharmacy monthly sequence).
--        avg_daily_units_sold_30d stubbed 0 until POS → days_of_cover stored NULL.

CREATE TABLE reorder_suggestion_snapshot (
    id                      UUID PRIMARY KEY,
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    product_id              UUID NOT NULL REFERENCES pharmacy_product (id),
    current_stock           INTEGER NOT NULL CHECK (current_stock >= 0),
    reorder_level           INTEGER NOT NULL CHECK (reorder_level > 0),
    days_of_cover           NUMERIC(5, 1),
    best_distributor_id     UUID REFERENCES distributors (id),
    landed_price_paise      BIGINT,
    snapshot_date           DATE NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_reorder_suggestion_pharmacy_product_date
    ON reorder_suggestion_snapshot (pharmacy_id, product_id, snapshot_date);

CREATE INDEX idx_reorder_suggestion_pharmacy_date
    ON reorder_suggestion_snapshot (pharmacy_id, snapshot_date);

CREATE TABLE purchase_order (
    id                  UUID PRIMARY KEY,
    pharmacy_id         UUID NOT NULL REFERENCES pharmacies (id),
    distributor_id      UUID NOT NULL REFERENCES distributors (id),
    po_number           VARCHAR(50) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by          UUID NOT NULL REFERENCES pharmacy_staff (id),
    sent_at             TIMESTAMPTZ,
    sent_channel        VARCHAR(16),
    grn_id              UUID REFERENCES purchase_grn (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT chk_purchase_order_status
        CHECK (status IN ('DRAFT', 'SENT', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_purchase_order_channel
        CHECK (sent_channel IS NULL OR sent_channel IN ('WHATSAPP', 'EMAIL'))
);

CREATE UNIQUE INDEX uq_purchase_order_pharmacy_number
    ON purchase_order (pharmacy_id, po_number)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_purchase_order_pharmacy_status
    ON purchase_order (pharmacy_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_purchase_order_pharmacy_distributor
    ON purchase_order (pharmacy_id, distributor_id)
    WHERE deleted_at IS NULL;

CREATE TABLE purchase_order_item (
    id                          UUID PRIMARY KEY,
    po_id                       UUID NOT NULL REFERENCES purchase_order (id),
    pharmacy_id                 UUID NOT NULL REFERENCES pharmacies (id),
    product_id                  UUID NOT NULL REFERENCES pharmacy_product (id),
    quantity                    INTEGER NOT NULL CHECK (quantity > 0),
    estimated_price_paise       BIGINT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_purchase_order_item_po
    ON purchase_order_item (po_id);

CREATE INDEX idx_purchase_order_item_pharmacy_product
    ON purchase_order_item (pharmacy_id, product_id);
