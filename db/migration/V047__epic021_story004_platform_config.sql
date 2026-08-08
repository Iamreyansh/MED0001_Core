-- EPIC-021 / STORY-004: platform_config + config_history
-- Rollback: DROP TABLE IF EXISTS config_history; DROP TABLE IF EXISTS platform_config;
-- Notes: seed-only keys (no create API); Redis cache key platform_config TTL 60s; immutable blocked in production.

CREATE TABLE platform_config (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT NOT NULL,
    type        VARCHAR(10) NOT NULL,
    unit        VARCHAR(20),
    domain      VARCHAR(20) NOT NULL,
    immutable   BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT NOT NULL,
    updated_by  UUID REFERENCES admin_staff (id),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_platform_config_type CHECK (
        type IN ('integer', 'decimal', 'boolean', 'string')
    ),
    CONSTRAINT chk_platform_config_domain CHECK (
        domain IN ('orders', 'payments', 'commissions', 'kyc', 'rider')
    )
);

CREATE INDEX idx_platform_config_domain ON platform_config (domain);

CREATE TABLE config_history (
    id          UUID PRIMARY KEY,
    key         VARCHAR(100) NOT NULL,
    old_value   TEXT,
    new_value   TEXT NOT NULL,
    changed_by  UUID NOT NULL REFERENCES admin_staff (id),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notes       TEXT
);

CREATE INDEX idx_config_history_key ON config_history (key);
CREATE INDEX idx_config_history_changed_at ON config_history (changed_at DESC);

INSERT INTO platform_config (key, value, type, unit, domain, immutable, description, updated_at)
VALUES
    ('orders.min_order_value', '49', 'integer', 'INR', 'orders', FALSE,
     'Minimum cart value required to place an order', TIMESTAMPTZ '2026-06-01 09:00:00+00'),
    ('orders.handling_fee', '5', 'integer', 'INR', 'orders', FALSE,
     'Fixed handling fee added to every order', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('orders.delivery_fee', '25', 'integer', 'INR', 'orders', FALSE,
     'Standard delivery fee per order', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('orders.free_delivery_threshold', '199', 'integer', 'INR', 'orders', FALSE,
     'Order value at or above which delivery fee is waived', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('orders.max_order_items', '20', 'integer', 'items', 'orders', FALSE,
     'Maximum number of distinct SKUs per order', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('orders.order_sla_minutes', '60', 'integer', 'minutes', 'orders', FALSE,
     'Target delivery time SLA from order confirmation', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('orders.order_id_prefix', 'NMM', 'string', NULL, 'orders', TRUE,
     'Structural order ID prefix; immutable in production', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('payments.max_wallet_credit_per_transaction', '1000', 'integer', 'INR', 'payments', FALSE,
     'Maximum single wallet credit an admin can issue', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('payments.refund_window_days', '7', 'integer', 'days', 'payments', FALSE,
     'Number of days post-delivery within which a refund can be initiated', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('payments.cod_available', 'true', 'boolean', NULL, 'payments', FALSE,
     'Global toggle for Cash on Delivery payment option', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('commissions.default_pharmacy_commission_pct', '8.5', 'decimal', '%', 'commissions', FALSE,
     'Default platform commission % applied to pharmacy payouts', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('commissions.min_commission_pct', '3.0', 'decimal', '%', 'commissions', FALSE,
     'Minimum allowable commission % for any pharmacy', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('commissions.max_commission_pct', '15.0', 'decimal', '%', 'commissions', FALSE,
     'Maximum allowable commission % for any pharmacy', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('kyc.kyc_auto_approve_enabled', 'false', 'boolean', NULL, 'kyc', FALSE,
     'If true, pharmacies with clean document submissions are auto-approved', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('kyc.kyc_document_expiry_warning_days', '30', 'integer', 'days', 'kyc', FALSE,
     'Days before document expiry to start sending renewal warnings', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('rider.cod_in_hand_limit', '5000', 'integer', 'INR', 'rider', FALSE,
     'Maximum COD cash a rider can hold before mandatory deposit', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('rider.rider_assignment_timeout_seconds', '120', 'integer', 'seconds', 'rider', FALSE,
     'Seconds to wait for a rider to accept an order before re-assigning', TIMESTAMPTZ '2025-06-01 09:00:00+00'),
    ('rider.auto_assign_enabled', 'true', 'boolean', NULL, 'rider', FALSE,
     'If true, orders are automatically assigned to available riders', TIMESTAMPTZ '2025-06-01 09:00:00+00');
