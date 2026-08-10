-- EPIC-013 / STORY-001: coupons + coupon_redemptions
-- Rollback:
--   DROP INDEX IF EXISTS idx_coupon_redemptions_redeemed_at;
--   DROP INDEX IF EXISTS idx_coupon_redemptions_customer;
--   DROP INDEX IF EXISTS idx_coupon_redemptions_coupon;
--   DROP TABLE IF EXISTS coupon_redemptions;
--   DROP INDEX IF EXISTS idx_coupons_status_active;
--   DROP INDEX IF EXISTS idx_coupons_code_upper;
--   DROP TABLE IF EXISTS coupons;
-- Notes: money as BIGINT paise (API exposes *_rs); PERCENTAGE uses percent_value INT 0-100;
--   FLAT_RS discount in value_paise; FREE_DELIVERY value_paise=0; segment_ids empty = all customers;
--   seeds NAMMA25/FLAT50/FREEDEL match CartPricing behavior (NAMMA25 max cap ₹100).

CREATE TABLE coupons (
    id                      UUID PRIMARY KEY,
    code                    VARCHAR(50) NOT NULL,
    type                    VARCHAR(20) NOT NULL,
    percent_value           INTEGER,
    value_paise             BIGINT,
    min_order_value_paise   BIGINT NOT NULL DEFAULT 0,
    max_discount_cap_paise  BIGINT,
    budget_total_paise      BIGINT NOT NULL,
    budget_used_paise       BIGINT NOT NULL DEFAULT 0,
    redemptions_count       INTEGER NOT NULL DEFAULT 0,
    max_redemptions_total   INTEGER,
    max_per_user            INTEGER NOT NULL DEFAULT 1,
    segment_ids             UUID[] NOT NULL DEFAULT '{}',
    is_first_order_only     BOOLEAN NOT NULL DEFAULT FALSE,
    is_rx_orders_only       BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from              TIMESTAMPTZ NOT NULL,
    valid_until             TIMESTAMPTZ NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    description             TEXT,
    terms                   TEXT,
    created_by              UUID REFERENCES admin_staff (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_coupons_type CHECK (type IN ('PERCENTAGE', 'FLAT_RS', 'FREE_DELIVERY')),
    CONSTRAINT chk_coupons_status CHECK (status IN ('ACTIVE', 'PAUSED', 'EXPIRED')),
    CONSTRAINT chk_coupons_percent CHECK (
        percent_value IS NULL OR (percent_value >= 0 AND percent_value <= 100)
    ),
    CONSTRAINT uq_coupons_code UNIQUE (code)
);

CREATE UNIQUE INDEX idx_coupons_code_upper ON coupons (UPPER(code));

CREATE INDEX idx_coupons_status_active
    ON coupons (status)
    WHERE status = 'ACTIVE';

CREATE TABLE coupon_redemptions (
    id                     UUID PRIMARY KEY,
    coupon_id              UUID NOT NULL REFERENCES coupons (id),
    order_id               UUID NOT NULL REFERENCES orders (id),
    customer_id            UUID NOT NULL REFERENCES customers (id),
    discount_applied_paise BIGINT NOT NULL,
    order_total_paise      BIGINT NOT NULL DEFAULT 0,
    redeemed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coupon_redemptions_coupon ON coupon_redemptions (coupon_id);
CREATE INDEX idx_coupon_redemptions_customer ON coupon_redemptions (customer_id);
CREATE INDEX idx_coupon_redemptions_redeemed_at ON coupon_redemptions (redeemed_at);

-- Seed platform coupons (stable UUIDs). Large budgets so cart smoke tests stay ACTIVE.
INSERT INTO coupons (
    id, code, type, percent_value, value_paise,
    min_order_value_paise, max_discount_cap_paise,
    budget_total_paise, budget_used_paise, redemptions_count,
    max_redemptions_total, max_per_user, segment_ids,
    is_first_order_only, is_rx_orders_only,
    valid_from, valid_until, status, description, terms, created_by
) VALUES
(
    'a0130001-0000-4000-8000-000000000001',
    'NAMMA25',
    'PERCENTAGE',
    25,
    NULL,
    19900,
    10000,
    5000000000,
    0,
    0,
    NULL,
    100,
    '{}',
    FALSE,
    FALSE,
    '2026-01-01T00:00:00Z',
    '2099-12-31T23:59:59Z',
    'ACTIVE',
    '25% off your order, max Rs 100',
    '25% off subtotal, max Rs 100 per order. Platform funded.',
    NULL
),
(
    'a0130001-0000-4000-8000-000000000002',
    'FLAT50',
    'FLAT_RS',
    NULL,
    5000,
    39900,
    5000,
    2500000000,
    0,
    0,
    NULL,
    100,
    '{}',
    FALSE,
    FALSE,
    '2026-01-01T00:00:00Z',
    '2099-12-31T23:59:59Z',
    'ACTIVE',
    'Rs 50 flat off on orders above Rs 399',
    'Valid once per user. Platform funded.',
    NULL
),
(
    'a0130001-0000-4000-8000-000000000003',
    'FREEDEL',
    'FREE_DELIVERY',
    NULL,
    0,
    0,
    NULL,
    1000000000,
    0,
    0,
    NULL,
    100,
    '{}',
    FALSE,
    FALSE,
    '2026-01-01T00:00:00Z',
    '2099-12-31T23:59:59Z',
    'ACTIVE',
    'Free delivery on your order',
    'Waives delivery fee. Platform funded.',
    NULL
);
