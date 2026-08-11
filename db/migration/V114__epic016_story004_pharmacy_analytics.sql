-- EPIC-016 / STORY-004: pharmacy analytics daily snapshots + report favorites
-- Rollback: DROP TABLE IF EXISTS pharmacy_report_favorites;
--           DROP TABLE IF EXISTS pharmacy_analytics_daily;
-- Notes: money BIGINT paise; channel ONLINE|COUNTER; COGS from product_batch when present;
--        dead_stock_flag refreshed daily 02:00 IST (on pharmacy_product via job, not this table);
--        FY Apr 1–Mar 31 IST.

CREATE TABLE pharmacy_analytics_daily (
    id                  UUID PRIMARY KEY,
    pharmacy_id         UUID NOT NULL REFERENCES pharmacies (id),
    snapshot_date       DATE NOT NULL,
    channel             VARCHAR(10) NOT NULL,
    revenue_paise       BIGINT NOT NULL DEFAULT 0,
    cogs_paise          BIGINT NOT NULL DEFAULT 0,
    gross_profit_paise  BIGINT NOT NULL DEFAULT 0,
    units_sold          INTEGER NOT NULL DEFAULT 0,
    output_gst_paise    BIGINT NOT NULL DEFAULT 0,
    input_itc_paise     BIGINT NOT NULL DEFAULT 0,
    orders_count        INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_pharmacy_analytics_daily UNIQUE (pharmacy_id, snapshot_date, channel),
    CONSTRAINT chk_pharmacy_analytics_channel CHECK (channel IN ('ONLINE', 'COUNTER'))
);

CREATE INDEX idx_pharmacy_analytics_daily_pharmacy_date
    ON pharmacy_analytics_daily (pharmacy_id, snapshot_date);

CREATE TABLE pharmacy_report_favorites (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    report_id       VARCHAR(50) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_report_favorites UNIQUE (pharmacy_id, report_id)
);

CREATE INDEX idx_pharmacy_report_favorites_pharmacy
    ON pharmacy_report_favorites (pharmacy_id);

-- Dead-stock materialization for nightly job (BR7); not in story table list but required for flag persistence.
ALTER TABLE pharmacy_product
    ADD COLUMN IF NOT EXISTS dead_stock_flag BOOLEAN NOT NULL DEFAULT FALSE;
