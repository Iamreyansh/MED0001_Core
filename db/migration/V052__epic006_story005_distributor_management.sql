-- EPIC-006 / STORY-005: expand distributors + distributor_supply_item
-- Rollback: DROP TABLE IF EXISTS distributor_supply_item;
--           ALTER TABLE distributors DROP COLUMN IF EXISTS contact_name, phone, email, gstin,
--             drug_licence_number, address, payment_terms_days, credit_limit_paise;
-- Notes: money as BIGINT paise. Soft deactivate uses is_active (deleted_at reserved for hard hide).
--        outstanding_payable is query-time from STOCKED GRNs (repayments deferred).

ALTER TABLE distributors
    ADD COLUMN IF NOT EXISTS contact_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(20),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS gstin VARCHAR(15),
    ADD COLUMN IF NOT EXISTS drug_licence_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS address TEXT,
    ADD COLUMN IF NOT EXISTS payment_terms_days INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS credit_limit_paise BIGINT NOT NULL DEFAULT 0;

-- Backfill phone for rows created by STORY-004 seeds (NOT NULL going forward).
UPDATE distributors
   SET phone = '+910000000000'
 WHERE phone IS NULL;

ALTER TABLE distributors
    ALTER COLUMN phone SET NOT NULL;

ALTER TABLE distributors
    DROP CONSTRAINT IF EXISTS chk_distributors_payment_terms;
ALTER TABLE distributors
    ADD CONSTRAINT chk_distributors_payment_terms CHECK (payment_terms_days >= 0);

ALTER TABLE distributors
    DROP CONSTRAINT IF EXISTS chk_distributors_credit_limit;
ALTER TABLE distributors
    ADD CONSTRAINT chk_distributors_credit_limit CHECK (credit_limit_paise >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_distributors_pharmacy_phone_active
    ON distributors (pharmacy_id, phone)
    WHERE deleted_at IS NULL AND is_active = TRUE;

CREATE TABLE distributor_supply_item (
    id                      UUID PRIMARY KEY,
    distributor_id          UUID NOT NULL REFERENCES distributors (id),
    product_id              UUID NOT NULL REFERENCES pharmacy_product (id),
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    purchase_price_paise    BIGINT NOT NULL CHECK (purchase_price_paise > 0),
    scheme_description      VARCHAR(100),
    is_preferred_source     BOOLEAN NOT NULL DEFAULT FALSE,
    last_purchased_at       TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_distributor_supply_item UNIQUE (distributor_id, product_id)
);

CREATE INDEX idx_distributor_supply_item_pharmacy_product
    ON distributor_supply_item (pharmacy_id, product_id);

CREATE INDEX idx_distributor_supply_item_distributor
    ON distributor_supply_item (distributor_id);

-- At most one preferred source per product within a pharmacy.
CREATE UNIQUE INDEX uq_distributor_supply_preferred_per_product
    ON distributor_supply_item (pharmacy_id, product_id)
    WHERE is_preferred_source = TRUE;
