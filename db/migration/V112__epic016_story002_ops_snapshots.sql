-- EPIC-016 / STORY-002: operations & SLA analytics snapshots
-- Rollback: DROP TABLE IF EXISTS analytics_cancellation_reasons;
--           DROP TABLE IF EXISTS analytics_ops_snapshots;
-- Notes: zone_id NULL = platform-wide; refreshed every 15m during 06:00–23:00 IST.
--        SLA default 45 minutes; per-zone override via zones.sla_minutes at refresh time.

CREATE TABLE analytics_ops_snapshots (
    id                          UUID PRIMARY KEY,
    snapshot_date               DATE NOT NULL,
    zone_id                     UUID NULL REFERENCES zones (id),
    sla_threshold_minutes       INTEGER NOT NULL DEFAULT 45,
    orders_placed               INTEGER NOT NULL DEFAULT 0,
    orders_accepted             INTEGER NOT NULL DEFAULT 0,
    orders_packed               INTEGER NOT NULL DEFAULT 0,
    orders_out_for_delivery     INTEGER NOT NULL DEFAULT 0,
    orders_delivered            INTEGER NOT NULL DEFAULT 0,
    orders_cancelled            INTEGER NOT NULL DEFAULT 0,
    sla_breached_count          INTEGER NOT NULL DEFAULT 0,
    total_prep_seconds          BIGINT NOT NULL DEFAULT 0,
    total_delivery_seconds      BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_analytics_ops_snapshots_platform
    ON analytics_ops_snapshots (snapshot_date)
    WHERE zone_id IS NULL;

CREATE UNIQUE INDEX uq_analytics_ops_snapshots_zone
    ON analytics_ops_snapshots (snapshot_date, zone_id)
    WHERE zone_id IS NOT NULL;

CREATE INDEX idx_analytics_ops_snapshots_date
    ON analytics_ops_snapshots (snapshot_date);

CREATE TABLE analytics_cancellation_reasons (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL REFERENCES orders (id),
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    zone_id         UUID NULL REFERENCES zones (id),
    cancel_stage    VARCHAR(15) NOT NULL,
    cancel_reason   VARCHAR(50) NOT NULL,
    cancel_actor    VARCHAR(10) NOT NULL,
    cancelled_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_analytics_cancellation_reasons_order UNIQUE (order_id),
    CONSTRAINT chk_analytics_cancel_stage
        CHECK (cancel_stage IN ('PRE_ACCEPT', 'POST_ACCEPT')),
    CONSTRAINT chk_analytics_cancel_actor
        CHECK (cancel_actor IN ('CUSTOMER', 'PHARMACY', 'SYSTEM'))
);

CREATE INDEX idx_analytics_cancellation_reasons_cancelled_at
    ON analytics_cancellation_reasons (cancelled_at);

CREATE INDEX idx_analytics_cancellation_reasons_zone
    ON analytics_cancellation_reasons (zone_id, cancelled_at);
