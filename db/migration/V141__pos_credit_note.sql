-- POS returns / credit notes against an immutable invoice.
-- Rollback: DROP TABLE IF EXISTS invoice_credit_note_item;
--           DROP TABLE IF EXISTS invoice_credit_note;

CREATE TABLE invoice_credit_note (
    id                  UUID PRIMARY KEY,
    pharmacy_id         UUID NOT NULL REFERENCES pharmacies (id),
    invoice_id          UUID NOT NULL REFERENCES invoice (id),
    credit_note_number  VARCHAR(40) NOT NULL,
    reason              VARCHAR(200) NOT NULL,
    total_paise         BIGINT NOT NULL CHECK (total_paise >= 0),
    created_by          UUID NOT NULL REFERENCES pharmacy_staff (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_credit_note_pharmacy_number UNIQUE (pharmacy_id, credit_note_number)
);

CREATE INDEX idx_credit_note_invoice ON invoice_credit_note (invoice_id);

CREATE TABLE invoice_credit_note_item (
    id                    UUID PRIMARY KEY,
    credit_note_id        UUID NOT NULL REFERENCES invoice_credit_note (id) ON DELETE CASCADE,
    invoice_item_id       UUID NOT NULL REFERENCES invoice_item (id),
    product_id            UUID NOT NULL,
    batch_id              UUID,
    quantity              INTEGER NOT NULL CHECK (quantity > 0),
    line_total_paise      BIGINT NOT NULL CHECK (line_total_paise >= 0),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_credit_note_item_note ON invoice_credit_note_item (credit_note_id);
