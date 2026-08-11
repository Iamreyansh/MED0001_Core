-- EPIC-016 / STORY-005: geography zone analytics + hourly demand heatmap
-- Rollback: DROP TABLE IF EXISTS analytics_zone_hourly_demand;
--           DROP TABLE IF EXISTS analytics_zone_daily;
-- Notes: money BIGINT paise; zone FK → zones; heatmap rolling 28-day avg recomputed nightly;
--        pharmacy_coverage_pct + unserved_attempts support supply-gap suggestions (EXPAND_ZONE);
--        riders_online for overview is live at request time (not stored here).

CREATE TABLE analytics_zone_daily (
    id                       UUID PRIMARY KEY,
    zone_id                  UUID NOT NULL REFERENCES zones (id),
    snapshot_date            DATE NOT NULL,
    gmv_paise                BIGINT NOT NULL DEFAULT 0,
    orders_count             INTEGER NOT NULL DEFAULT 0,
    sla_breached_count       INTEGER NOT NULL DEFAULT 0,
    total_delivery_seconds   BIGINT NOT NULL DEFAULT 0,
    avg_riders_online        NUMERIC(5, 2) NOT NULL DEFAULT 0,
    pharmacies_count         INTEGER NOT NULL DEFAULT 0,
    pharmacy_coverage_pct    NUMERIC(5, 2) NOT NULL DEFAULT 0,
    unserved_attempts        INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_analytics_zone_daily UNIQUE (zone_id, snapshot_date)
);

CREATE INDEX idx_analytics_zone_daily_date
    ON analytics_zone_daily (snapshot_date);

CREATE INDEX idx_analytics_zone_daily_zone_date
    ON analytics_zone_daily (zone_id, snapshot_date);

CREATE TABLE analytics_zone_hourly_demand (
    id            UUID PRIMARY KEY,
    zone_id       UUID NOT NULL REFERENCES zones (id),
    hour_of_day   SMALLINT NOT NULL,
    day_of_week   SMALLINT NOT NULL,
    avg_orders    NUMERIC(6, 2) NOT NULL DEFAULT 0,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analytics_zone_hourly UNIQUE (zone_id, hour_of_day, day_of_week),
    CONSTRAINT chk_zone_hourly_hour CHECK (hour_of_day BETWEEN 0 AND 23),
    CONSTRAINT chk_zone_hourly_dow CHECK (day_of_week BETWEEN 0 AND 6)
);

CREATE INDEX idx_analytics_zone_hourly_zone
    ON analytics_zone_hourly_demand (zone_id);
