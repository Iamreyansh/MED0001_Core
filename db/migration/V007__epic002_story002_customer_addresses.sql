-- EPIC-002 / STORY-002: delivery address book + default_address_id
-- Rollback:
--   ALTER TABLE customers DROP COLUMN IF EXISTS default_address_id;
--   DROP INDEX IF EXISTS idx_customer_addresses_one_default;
--   DROP INDEX IF EXISTS idx_customer_addresses_customer_id;
--   DROP TABLE IF EXISTS customer_addresses;
-- Notes: soft delete via deleted_at; one default per customer enforced by partial unique index;
--   default_address_id denormalised on customers for BR-8 (cleared when last address deleted).

CREATE TABLE customer_addresses (
    id              UUID PRIMARY KEY,
    customer_id     UUID NOT NULL REFERENCES customers (id),
    label           VARCHAR(10) NOT NULL,
    flat_building   VARCHAR(200) NOT NULL,
    area_locality   VARCHAR(200) NOT NULL,
    city            VARCHAR(100) NOT NULL,
    state           VARCHAR(100) NOT NULL,
    pincode         CHAR(6) NOT NULL,
    latitude        NUMERIC(10, 7) NOT NULL,
    longitude       NUMERIC(10, 7) NOT NULL,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL,
    deleted_at      TIMESTAMPTZ NULL
);

CREATE INDEX idx_customer_addresses_customer_id
    ON customer_addresses (customer_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX idx_customer_addresses_one_default
    ON customer_addresses (customer_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;

ALTER TABLE customers
    ADD COLUMN default_address_id UUID NULL REFERENCES customer_addresses (id);
