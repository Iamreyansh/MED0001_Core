-- EPIC-011 / STORY-006: delivery fee pricing
-- Rollback:
--   DROP TABLE IF EXISTS delivery_fee_snapshots;
--   DROP TABLE IF EXISTS platform_pricing_config;
-- Notes: Zone fee columns already on `zones` (V039). Handling fee is platform-wide.
--        Snapshot money stored as DECIMAL rupees (matches zone fee columns / story contract).
--        Order bill lines remain BIGINT paise on `orders`.

CREATE TABLE IF NOT EXISTS platform_pricing_config (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT NOT NULL,
    description TEXT,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO platform_pricing_config (key, value, description, updated_at)
VALUES (
    'handling_fee',
    '5.00',
    'Platform handling fee in Rs (always charged; not surged or waived)',
    NOW()
)
ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS delivery_fee_snapshots (
    order_id          UUID PRIMARY KEY REFERENCES orders (id),
    zone_id           UUID NOT NULL REFERENCES zones (id),
    distance_km       NUMERIC(6, 2) NOT NULL,
    base_fee          NUMERIC(8, 2) NOT NULL,
    distance_charge   NUMERIC(8, 2) NOT NULL,
    surge_multiplier  NUMERIC(4, 2) NOT NULL,
    delivery_fee      NUMERIC(8, 2) NOT NULL,
    handling_fee      NUMERIC(8, 2) NOT NULL,
    is_free_delivery  BOOLEAN NOT NULL,
    rider_payout      NUMERIC(8, 2) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_delivery_fee_snapshots_zone
    ON delivery_fee_snapshots (zone_id);
