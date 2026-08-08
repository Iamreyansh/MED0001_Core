-- EPIC-010 / STORY-001: customer carts (JSONB items, one ACTIVE per customer)
-- Rollback: DROP INDEX IF EXISTS uq_carts_one_active_per_customer;
--           DROP INDEX IF EXISTS idx_carts_customer_status;
--           DROP TABLE IF EXISTS carts;
-- Notes: money stored as BIGINT paise; items JSONB snapshots unit_price_paise at add time.

CREATE TABLE carts (
    id                     UUID PRIMARY KEY,
    customer_id            UUID NOT NULL REFERENCES customers (id),
    pharmacy_id            UUID NULL REFERENCES pharmacies (id),
    items                  JSONB NOT NULL DEFAULT '[]'::jsonb,
    coupon_code            VARCHAR(20) NULL,
    coupon_discount_paise  BIGINT NOT NULL DEFAULT 0,
    prescription_id        UUID NULL,
    delivery_address_id    UUID NULL REFERENCES customer_addresses (id),
    status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_carts_status CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'ABANDONED'))
);

CREATE UNIQUE INDEX uq_carts_one_active_per_customer
    ON carts (customer_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_carts_customer_status
    ON carts (customer_id, status);

CREATE INDEX idx_carts_abandon_scan
    ON carts (updated_at)
    WHERE status = 'ACTIVE';
