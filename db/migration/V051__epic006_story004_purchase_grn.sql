-- EPIC-006 / STORY-004: distributors (minimal) + purchase_grn + purchase_grn_item
-- Rollback: DROP TABLE IF EXISTS purchase_grn_item;
--           DROP TABLE IF EXISTS purchase_grn;
--           DROP TABLE IF EXISTS distributors;
-- Notes: money as BIGINT paise. Full distributor APIs land in STORY-005.
--        Soft-deleted distributors remain for FK + duplicate invoice checks.
--        import_unmatched JSONB holds CSV preview rows pending confirm-import.

CREATE TABLE distributors (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    firm_name       VARCHAR(200) NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_distributors_pharmacy
    ON distributors (pharmacy_id)
    WHERE deleted_at IS NULL;

CREATE TABLE purchase_grn (
    id                  UUID PRIMARY KEY,
    pharmacy_id         UUID NOT NULL REFERENCES pharmacies (id),
    distributor_id      UUID NOT NULL REFERENCES distributors (id),
    invoice_number      VARCHAR(100) NOT NULL,
    invoice_date        DATE NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    stocked_at          TIMESTAMPTZ,
    stocked_by          UUID REFERENCES pharmacy_staff (id),
    created_by          UUID NOT NULL REFERENCES pharmacy_staff (id),
    import_unmatched    JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT chk_purchase_grn_status CHECK (status IN ('DRAFT', 'SAVED', 'STOCKED'))
);

CREATE UNIQUE INDEX uq_purchase_grn_invoice_active
    ON purchase_grn (pharmacy_id, distributor_id, invoice_number)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_purchase_grn_pharmacy_status
    ON purchase_grn (pharmacy_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_purchase_grn_pharmacy_invoice_date
    ON purchase_grn (pharmacy_id, invoice_date DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE purchase_grn_item (
    id                      UUID PRIMARY KEY,
    grn_id                  UUID NOT NULL REFERENCES purchase_grn (id),
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    product_id              UUID NOT NULL REFERENCES pharmacy_product (id),
    batch_number            VARCHAR(50) NOT NULL,
    expiry_date             DATE NOT NULL,
    manufactured_date       DATE,
    quantity                INTEGER NOT NULL CHECK (quantity > 0),
    free_quantity           INTEGER NOT NULL DEFAULT 0 CHECK (free_quantity >= 0),
    purchase_price_paise    BIGINT NOT NULL CHECK (purchase_price_paise > 0),
    mrp_paise               BIGINT NOT NULL CHECK (mrp_paise > 0),
    gst_pct                 SMALLINT NOT NULL,
    taxable_amount_paise    BIGINT NOT NULL CHECK (taxable_amount_paise >= 0),
    gst_amount_paise        BIGINT NOT NULL CHECK (gst_amount_paise >= 0),
    line_total_paise        BIGINT NOT NULL CHECK (line_total_paise >= 0),
    is_new_product          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_purchase_grn_item_gst CHECK (gst_pct IN (0, 5, 12, 18, 28))
);

CREATE INDEX idx_purchase_grn_item_grn
    ON purchase_grn_item (grn_id);

CREATE INDEX idx_purchase_grn_item_pharmacy_product
    ON purchase_grn_item (pharmacy_id, product_id);
