-- EPIC-007 / STORY-003: Credit / Khata ledger
-- Rollback:
--   DROP TABLE IF EXISTS khata_reminder_log;
--   DROP TABLE IF EXISTS khata_entry;
--   DROP TABLE IF EXISTS khata_repayment;
--   DROP TABLE IF EXISTS khata_customer_limit;
--   DROP TABLE IF EXISTS receipt_number_sequence;
-- Notes: Money BIGINT paise. Default credit limit ₹50,000 = 5_000_000 paise.
--        Aging/KPIs computed at query time. Ledger is append-only.

CREATE TABLE receipt_number_sequence (
    pharmacy_id UUID NOT NULL REFERENCES pharmacies (id),
    year        SMALLINT NOT NULL,
    month       SMALLINT NOT NULL CHECK (month BETWEEN 1 AND 12),
    last_seq    INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    PRIMARY KEY (pharmacy_id, year, month)
);

CREATE TABLE khata_customer_limit (
    pharmacy_id        UUID NOT NULL REFERENCES pharmacies (id),
    customer_id        UUID NOT NULL REFERENCES customers (id),
    credit_limit_paise BIGINT NOT NULL DEFAULT 5000000 CHECK (credit_limit_paise > 0),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pharmacy_id, customer_id)
);

CREATE TABLE khata_repayment (
    id                 UUID PRIMARY KEY,
    pharmacy_id        UUID NOT NULL REFERENCES pharmacies (id),
    customer_id        UUID NOT NULL REFERENCES customers (id),
    receipt_number     VARCHAR(30) NOT NULL,
    amount_paise       BIGINT NOT NULL CHECK (amount_paise > 0),
    payment_mode       VARCHAR(16) NOT NULL,
    reference_number   VARCHAR(50),
    notes              TEXT,
    collected_by       UUID NOT NULL,
    outstanding_after_paise BIGINT NOT NULL CHECK (outstanding_after_paise >= 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_khata_repayment_pharmacy_receipt UNIQUE (pharmacy_id, receipt_number),
    CONSTRAINT chk_khata_repayment_mode CHECK (payment_mode IN ('CASH', 'UPI', 'CARD'))
);

CREATE INDEX idx_khata_repayment_pharmacy_created
    ON khata_repayment (pharmacy_id, created_at DESC);
CREATE INDEX idx_khata_repayment_customer
    ON khata_repayment (pharmacy_id, customer_id, created_at DESC);

CREATE TABLE khata_entry (
    id                   UUID PRIMARY KEY,
    pharmacy_id          UUID NOT NULL REFERENCES pharmacies (id),
    customer_id          UUID NOT NULL REFERENCES customers (id),
    type                 VARCHAR(8) NOT NULL,
    amount_paise         BIGINT NOT NULL CHECK (amount_paise > 0),
    invoice_id           UUID REFERENCES invoice (id),
    repayment_id         UUID REFERENCES khata_repayment (id),
    reference_number     VARCHAR(50) NOT NULL,
    notes                TEXT,
    running_balance_paise BIGINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_khata_entry_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_khata_entry_source CHECK (
        (type = 'DEBIT' AND invoice_id IS NOT NULL AND repayment_id IS NULL)
        OR (type = 'CREDIT' AND repayment_id IS NOT NULL)
    )
);

CREATE INDEX idx_khata_entry_pharmacy_customer
    ON khata_entry (pharmacy_id, customer_id, created_at);
CREATE INDEX idx_khata_entry_pharmacy_created
    ON khata_entry (pharmacy_id, created_at DESC);
CREATE INDEX idx_khata_entry_invoice
    ON khata_entry (invoice_id)
    WHERE invoice_id IS NOT NULL;

CREATE TABLE khata_reminder_log (
    id           UUID PRIMARY KEY,
    pharmacy_id  UUID NOT NULL REFERENCES pharmacies (id),
    customer_id  UUID NOT NULL REFERENCES customers (id),
    channel      VARCHAR(16) NOT NULL,
    template     VARCHAR(16) NOT NULL,
    message_id   VARCHAR(100) NOT NULL,
    sent_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_khata_reminder_channel CHECK (channel IN ('WHATSAPP', 'SMS')),
    CONSTRAINT chk_khata_reminder_template CHECK (template IN ('POLITE', 'FIRM'))
);

CREATE INDEX idx_khata_reminder_customer_sent
    ON khata_reminder_log (pharmacy_id, customer_id, sent_at DESC);
