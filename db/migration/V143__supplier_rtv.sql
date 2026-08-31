-- Supplier return-to-vendor against a STOCKED GRN.
-- Rollback: DROP TABLE IF EXISTS supplier_rtv_item;
--           DROP TABLE IF EXISTS supplier_rtv;

CREATE TABLE supplier_rtv (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    grn_id          UUID NOT NULL REFERENCES purchase_grn (id),
    rtv_number      VARCHAR(40) NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    created_by      UUID NOT NULL REFERENCES pharmacy_staff (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_supplier_rtv_pharmacy_number UNIQUE (pharmacy_id, rtv_number),
    CONSTRAINT chk_supplier_rtv_status CHECK (status IN ('POSTED'))
);

CREATE INDEX idx_supplier_rtv_grn ON supplier_rtv (grn_id);

CREATE TABLE supplier_rtv_item (
    id                UUID PRIMARY KEY,
    rtv_id            UUID NOT NULL REFERENCES supplier_rtv (id) ON DELETE CASCADE,
    grn_item_id       UUID NOT NULL REFERENCES purchase_grn_item (id),
    product_id        UUID NOT NULL,
    batch_id          UUID,
    quantity          INTEGER NOT NULL CHECK (quantity > 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_supplier_rtv_item_rtv ON supplier_rtv_item (rtv_id);
