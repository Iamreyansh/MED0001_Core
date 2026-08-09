-- EPIC-007 / STORY-001: POS cart + invoice (counter sale)
-- Rollback:
--   DROP TABLE IF EXISTS invoice_item;
--   DROP TABLE IF EXISTS invoice;
--   DROP TABLE IF EXISTS invoice_number_sequence;
--   DROP TABLE IF EXISTS invoice_settings;
--   DROP TABLE IF EXISTS pos_cart_item;
--   DROP TABLE IF EXISTS pos_cart;
--   DROP INDEX IF EXISTS uq_pharmacy_product_barcode;
--   ALTER TABLE pharmacy_product DROP COLUMN IF EXISTS barcode;
-- Notes: All money columns are BIGINT paise. API layer converts to BigDecimal rupees scale 2.
--        Invoice list/PDF/share APIs are STORY-002; checkout here creates invoice + items.
--        Khata CREDIT ledger posts deferred to STORY-003 (port stub).

ALTER TABLE pharmacy_product
    ADD COLUMN IF NOT EXISTS barcode VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pharmacy_product_barcode
    ON pharmacy_product (pharmacy_id, barcode)
    WHERE deleted_at IS NULL AND barcode IS NOT NULL;

CREATE TABLE pos_cart (
    id                    UUID PRIMARY KEY,
    pharmacy_id           UUID NOT NULL REFERENCES pharmacies (id),
    staff_id              UUID NOT NULL,
    customer_id           UUID REFERENCES customers (id),
    customer_name         VARCHAR(100),
    customer_phone        VARCHAR(20),
    prescribing_doctor    VARCHAR(200),
    discount_type         VARCHAR(16),
    discount_value        NUMERIC(10, 2) NOT NULL DEFAULT 0,
    discount_amount_paise BIGINT NOT NULL DEFAULT 0 CHECK (discount_amount_paise >= 0),
    subtotal_paise        BIGINT NOT NULL DEFAULT 0 CHECK (subtotal_paise >= 0),
    gst_total_paise       BIGINT NOT NULL DEFAULT 0 CHECK (gst_total_paise >= 0),
    grand_total_paise     BIGINT NOT NULL DEFAULT 0 CHECK (grand_total_paise >= 0),
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at            TIMESTAMPTZ NOT NULL,
    invoice_id            UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pos_cart_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT chk_pos_cart_discount_type CHECK (
        discount_type IS NULL OR discount_type IN ('FLAT_RS', 'PERCENTAGE')
    )
);

CREATE INDEX idx_pos_cart_pharmacy_status ON pos_cart (pharmacy_id, status);
CREATE INDEX idx_pos_cart_expires ON pos_cart (expires_at)
    WHERE status = 'ACTIVE';

CREATE TABLE pos_cart_item (
    id                  UUID PRIMARY KEY,
    cart_id             UUID NOT NULL REFERENCES pos_cart (id) ON DELETE CASCADE,
    product_id          UUID NOT NULL,
    product_name        VARCHAR(200) NOT NULL,
    batch_id            UUID NOT NULL,
    batch_number        VARCHAR(64) NOT NULL,
    expiry_date         DATE NOT NULL,
    quantity            INTEGER NOT NULL CHECK (quantity > 0),
    is_loose            BOOLEAN NOT NULL DEFAULT FALSE,
    unit_price_paise    BIGINT NOT NULL CHECK (unit_price_paise >= 0),
    gst_pct             SMALLINT NOT NULL,
    line_subtotal_paise BIGINT NOT NULL CHECK (line_subtotal_paise >= 0),
    gst_amount_paise    BIGINT NOT NULL CHECK (gst_amount_paise >= 0),
    line_total_paise    BIGINT NOT NULL CHECK (line_total_paise >= 0),
    is_rx_only          BOOLEAN NOT NULL DEFAULT FALSE,
    pack_size           INTEGER NOT NULL DEFAULT 1,
    hsn_code            VARCHAR(8),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pos_cart_item_gst CHECK (gst_pct IN (0, 5, 12, 18, 28))
);

CREATE INDEX idx_pos_cart_item_cart ON pos_cart_item (cart_id);

CREATE TABLE invoice_settings (
    pharmacy_id           UUID PRIMARY KEY REFERENCES pharmacies (id),
    template              VARCHAR(16) NOT NULL DEFAULT 'MODERN',
    accent_color          VARCHAR(7) NOT NULL DEFAULT '#2563EB',
    logo_url              TEXT,
    signature_url         TEXT,
    document_title        VARCHAR(50) NOT NULL DEFAULT 'Tax Invoice',
    invoice_prefix        VARCHAR(6) NOT NULL DEFAULT 'INV',
    signatory_label       VARCHAR(100) NOT NULL DEFAULT 'Authorized Signatory',
    bank_details          JSONB,
    terms_and_conditions  TEXT,
    footer_note           VARCHAR(500),
    show_mrp_savings      BOOLEAN NOT NULL DEFAULT TRUE,
    show_doctor           BOOLEAN NOT NULL DEFAULT TRUE,
    show_hsn              BOOLEAN NOT NULL DEFAULT TRUE,
    print_bank_details    BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_invoice_settings_template CHECK (template IN ('MODERN', 'MINIMAL', 'THERMAL'))
);

CREATE TABLE invoice_number_sequence (
    pharmacy_id UUID NOT NULL REFERENCES pharmacies (id),
    year        SMALLINT NOT NULL,
    month       SMALLINT NOT NULL CHECK (month BETWEEN 1 AND 12),
    last_seq    INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    PRIMARY KEY (pharmacy_id, year, month)
);

CREATE TABLE invoice (
    id                    UUID PRIMARY KEY,
    pharmacy_id           UUID NOT NULL REFERENCES pharmacies (id),
    invoice_number        VARCHAR(30) NOT NULL,
    cart_id               UUID REFERENCES pos_cart (id),
    channel               VARCHAR(16) NOT NULL,
    customer_id           UUID REFERENCES customers (id),
    customer_name         VARCHAR(100),
    customer_phone        VARCHAR(20),
    prescribing_doctor    VARCHAR(200),
    subtotal_paise        BIGINT NOT NULL CHECK (subtotal_paise >= 0),
    discount_amount_paise BIGINT NOT NULL DEFAULT 0 CHECK (discount_amount_paise >= 0),
    gst_total_paise       BIGINT NOT NULL CHECK (gst_total_paise >= 0),
    grand_total_paise     BIGINT NOT NULL CHECK (grand_total_paise >= 0),
    payment_method        VARCHAR(32) NOT NULL,
    payment_status        VARCHAR(16) NOT NULL,
    payment_reference     VARCHAR(100),
    amount_paid_paise     BIGINT NOT NULL DEFAULT 0 CHECK (amount_paid_paise >= 0),
    change_due_paise      BIGINT NOT NULL DEFAULT 0 CHECK (change_due_paise >= 0),
    mrp_savings_paise     BIGINT NOT NULL DEFAULT 0 CHECK (mrp_savings_paise >= 0),
    status                VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    invoice_pdf_url       TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_invoice_pharmacy_number UNIQUE (pharmacy_id, invoice_number),
    CONSTRAINT chk_invoice_channel CHECK (channel IN ('COUNTER', 'ONLINE')),
    CONSTRAINT chk_invoice_payment_method CHECK (payment_method IN (
        'CASH', 'UPI', 'CARD', 'COD', 'CREDIT', 'INSURANCE_TPA'
    )),
    CONSTRAINT chk_invoice_payment_status CHECK (payment_status IN ('PAID', 'PENDING', 'PARTIAL')),
    CONSTRAINT chk_invoice_status CHECK (status IN ('ACTIVE', 'CREDIT_NOTE_ISSUED'))
);

CREATE INDEX idx_invoice_pharmacy_created ON invoice (pharmacy_id, created_at DESC);

CREATE TABLE invoice_item (
    id                  UUID PRIMARY KEY,
    invoice_id          UUID NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    product_id          UUID NOT NULL,
    product_name        VARCHAR(200) NOT NULL,
    hsn_code            VARCHAR(8),
    batch_id            UUID,
    batch_number        VARCHAR(64),
    expiry_date         DATE,
    pack_size           INTEGER,
    quantity            INTEGER NOT NULL CHECK (quantity > 0),
    is_loose            BOOLEAN NOT NULL DEFAULT FALSE,
    unit_price_paise    BIGINT NOT NULL CHECK (unit_price_paise >= 0),
    gst_pct             SMALLINT NOT NULL,
    line_subtotal_paise BIGINT NOT NULL CHECK (line_subtotal_paise >= 0),
    gst_amount_paise    BIGINT NOT NULL CHECK (gst_amount_paise >= 0),
    line_total_paise    BIGINT NOT NULL CHECK (line_total_paise >= 0),
    is_rx_only          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_invoice_item_gst CHECK (gst_pct IN (0, 5, 12, 18, 28))
);

CREATE INDEX idx_invoice_item_invoice ON invoice_item (invoice_id);

-- FK from pos_cart.invoice_id after invoice exists
ALTER TABLE pos_cart
    ADD CONSTRAINT fk_pos_cart_invoice
        FOREIGN KEY (invoice_id) REFERENCES invoice (id);
