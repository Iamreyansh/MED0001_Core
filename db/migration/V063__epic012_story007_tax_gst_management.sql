-- EPIC-012 / STORY-007: tax & GST management
-- Rollback:
--   DROP TABLE IF EXISTS tcs_register;
--   DROP TABLE IF EXISTS tax_filing;
-- Notes: Money as BIGINT paise (not DECIMAL rupees). Soft-delete only — retain 7 years (no hard purge).
--        TCS register upserted on settlement release (STORY-003); amounts are read-only via tax APIs.

CREATE TABLE tax_filing (
    id                UUID PRIMARY KEY,
    filing_type       VARCHAR(16)  NOT NULL,
    period            VARCHAR(10)  NOT NULL,
    due_date          DATE         NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    filed_at          TIMESTAMPTZ,
    reference_number  VARCHAR(100),
    notes             TEXT,
    marked_by         UUID,
    generated_files   JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT chk_tax_filing_type CHECK (
        filing_type IN ('GSTR-8', 'TDS-194O', 'GSTR-1', 'GSTR-3B')
    ),
    CONSTRAINT chk_tax_filing_status CHECK (
        status IN ('PENDING', 'FILED', 'OVERDUE')
    )
);

CREATE UNIQUE INDEX uq_tax_filing_type_period_active
    ON tax_filing (filing_type, period)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_tax_filing_status_due
    ON tax_filing (status, due_date)
    WHERE deleted_at IS NULL;

CREATE TABLE tcs_register (
    id                 UUID PRIMARY KEY,
    pharmacy_id        UUID         NOT NULL,
    month              CHAR(7)      NOT NULL,
    pharmacy_name      VARCHAR(255) NOT NULL DEFAULT '',
    gstin              VARCHAR(15)  NOT NULL DEFAULT '',
    pan                VARCHAR(10)  NOT NULL DEFAULT '',
    gmv_paise          BIGINT       NOT NULL DEFAULT 0,
    tcs_collected_paise BIGINT      NOT NULL DEFAULT 0,
    cgst_tcs_paise     BIGINT       NOT NULL DEFAULT 0,
    sgst_tcs_paise     BIGINT       NOT NULL DEFAULT 0,
    settlement_ids     UUID[]       NOT NULL DEFAULT '{}',
    gstr8_filing_id    UUID         REFERENCES tax_filing (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT chk_tcs_register_money CHECK (
        gmv_paise >= 0
        AND tcs_collected_paise >= 0
        AND cgst_tcs_paise >= 0
        AND sgst_tcs_paise >= 0
    )
);

CREATE UNIQUE INDEX uq_tcs_register_pharmacy_month_active
    ON tcs_register (pharmacy_id, month)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_tcs_register_month
    ON tcs_register (month)
    WHERE deleted_at IS NULL;
