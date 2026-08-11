-- EPIC-016 / STORY-001: platform overview analytics daily snapshots
-- Rollback: DROP TABLE IF EXISTS analytics_category_mix_daily;
--           DROP TABLE IF EXISTS analytics_payment_mix_daily;
--           DROP TABLE IF EXISTS analytics_daily_snapshots;
-- Notes: money BIGINT paise; zone_id NULL = platform-wide row; refreshed 02:00 IST.

CREATE TABLE analytics_daily_snapshots (
    id                  UUID PRIMARY KEY,
    snapshot_date       DATE NOT NULL,
    gmv_paise           BIGINT NOT NULL DEFAULT 0,
    orders_count        INTEGER NOT NULL DEFAULT 0,
    delivered_count     INTEGER NOT NULL DEFAULT 0,
    cancelled_count     INTEGER NOT NULL DEFAULT 0,
    net_revenue_paise   BIGINT NOT NULL DEFAULT 0,
    commission_paise    BIGINT NOT NULL DEFAULT 0,
    refunds_paise       BIGINT NOT NULL DEFAULT 0,
    cancellations_paise BIGINT NOT NULL DEFAULT 0,
    cogs_estimate_paise BIGINT NOT NULL DEFAULT 0,
    active_customers    INTEGER NOT NULL DEFAULT 0,
    repeat_customers    INTEGER NOT NULL DEFAULT 0,
    new_customers       INTEGER NOT NULL DEFAULT 0,
    zone_id             UUID NULL REFERENCES zones (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- NULL zone_id = platform-wide; partial uniques so ON CONFLICT works in Postgres.
CREATE UNIQUE INDEX uq_analytics_daily_snapshots_platform
    ON analytics_daily_snapshots (snapshot_date)
    WHERE zone_id IS NULL;

CREATE UNIQUE INDEX uq_analytics_daily_snapshots_zone
    ON analytics_daily_snapshots (snapshot_date, zone_id)
    WHERE zone_id IS NOT NULL;

CREATE INDEX idx_analytics_daily_snapshots_date
    ON analytics_daily_snapshots (snapshot_date);

CREATE TABLE analytics_payment_mix_daily (
    id              UUID PRIMARY KEY,
    snapshot_date   DATE NOT NULL,
    payment_method  VARCHAR(30) NOT NULL,
    orders_count    INTEGER NOT NULL DEFAULT 0,
    gmv_paise       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_analytics_payment_mix_daily UNIQUE (snapshot_date, payment_method),
    CONSTRAINT chk_analytics_payment_mix_method
        CHECK (payment_method IN ('UPI', 'CARD', 'COD', 'WALLET'))
);

CREATE INDEX idx_analytics_payment_mix_daily_date
    ON analytics_payment_mix_daily (snapshot_date);

CREATE TABLE analytics_category_mix_daily (
    id              UUID PRIMARY KEY,
    snapshot_date   DATE NOT NULL,
    category        VARCHAR(50) NOT NULL,
    gmv_paise       BIGINT NOT NULL DEFAULT 0,
    units_sold      INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_analytics_category_mix_daily UNIQUE (snapshot_date, category)
);

CREATE INDEX idx_analytics_category_mix_daily_date
    ON analytics_category_mix_daily (snapshot_date);
