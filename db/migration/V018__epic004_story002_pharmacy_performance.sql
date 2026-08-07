-- EPIC-004 / STORY-002: pharmacy performance snapshots + admin alerts
-- Rollback: DROP TABLE IF EXISTS performance_alert;
--           DROP TABLE IF EXISTS pharmacy_performance_snapshot;
--           DROP TYPE IF EXISTS performance_alert_type;
--           DROP TYPE IF EXISTS pharmacy_performance_period;
-- Notes: nightly aggregator at 02:00 IST; Redis cache key pharmacy:perf:snapshot:{pharmacyId}:{period} TTL 4h

CREATE TYPE pharmacy_performance_period AS ENUM ('7D', '30D', '90D');

CREATE TYPE performance_alert_type AS ENUM (
    'LOW_FILL_RATE',
    'HIGH_CANCEL_RATE',
    'OFFLINE_PEAK_HOURS',
    'LOW_RATING',
    'HIGH_OOS_RATE',
    'SLOW_PREP_TIME'
);

CREATE TABLE IF NOT EXISTS pharmacy_performance_snapshot (
    id                      UUID PRIMARY KEY,
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    period                  pharmacy_performance_period NOT NULL,
    period_start            DATE NOT NULL,
    period_end              DATE NOT NULL,
    orders_received         INTEGER NOT NULL DEFAULT 0,
    orders_fulfilled        INTEGER NOT NULL DEFAULT 0,
    orders_cancelled          INTEGER NOT NULL DEFAULT 0,
    fill_rate_pct           NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    on_time_prep_pct        NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    cancel_rate_pct         NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    out_of_stock_rate_pct   NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    avg_prep_minutes        NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    complaint_count         INTEGER NOT NULL DEFAULT 0,
    avg_rating              NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    review_count            INTEGER NOT NULL DEFAULT 0,
    gmv_period_paise        BIGINT NOT NULL DEFAULT 0,
    consecutive_low_fill_days SMALLINT NOT NULL DEFAULT 0,
    fill_rate_trend         VARCHAR(16) NOT NULL DEFAULT 'STABLE',
    cancel_rate_trend       VARCHAR(16) NOT NULL DEFAULT 'STABLE',
    computed_at             TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_performance_snapshot UNIQUE (pharmacy_id, period)
);

CREATE INDEX IF NOT EXISTS idx_pharmacy_performance_snapshot_pharmacy
    ON pharmacy_performance_snapshot (pharmacy_id);

CREATE TABLE IF NOT EXISTS performance_alert (
    id                UUID PRIMARY KEY,
    pharmacy_id       UUID NOT NULL REFERENCES pharmacies (id),
    alert_type        performance_alert_type NOT NULL,
    triggered_by      UUID,
    threshold_value   NUMERIC(10, 2) NOT NULL,
    message           TEXT,
    channels          TEXT[] NOT NULL,
    sent_at           TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_performance_alert_pharmacy_type_sent
    ON performance_alert (pharmacy_id, alert_type, sent_at DESC);
