-- EPIC-007 / STORY-005: pharmacy offers & discount redemptions
-- Rollback:
--   ALTER TABLE pos_cart DROP COLUMN IF EXISTS applied_offer_id;
--   DROP TABLE IF EXISTS offer_redemption;
--   DROP TABLE IF EXISTS pharmacy_offer;
-- Notes: Flat discount amounts stored as BIGINT paise. PERCENTAGE discount_value is whole percent (e.g. 10).
--        Admin /api/v1/admin/pharmacy-offers is out of scope for this story.

CREATE TABLE pharmacy_offer (
    id                  UUID PRIMARY KEY,
    pharmacy_id         UUID NOT NULL REFERENCES pharmacies (id),
    title               VARCHAR(200) NOT NULL,
    coupon_code         VARCHAR(20) NOT NULL,
    discount_type       VARCHAR(16) NOT NULL,
    discount_value      BIGINT NOT NULL CHECK (discount_value > 0),
    applies_to          VARCHAR(16) NOT NULL,
    scope_ids           UUID[],
    is_online           BOOLEAN NOT NULL DEFAULT FALSE,
    is_counter          BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from          DATE NOT NULL,
    valid_until         DATE NOT NULL,
    max_redemptions     INTEGER NOT NULL DEFAULT 0 CHECK (max_redemptions >= 0),
    total_redemptions   INTEGER NOT NULL DEFAULT 0 CHECK (total_redemptions >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_offer_coupon UNIQUE (pharmacy_id, coupon_code),
    CONSTRAINT chk_pharmacy_offer_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FLAT_RS')),
    CONSTRAINT chk_pharmacy_offer_applies_to CHECK (applies_to IN ('ALL', 'CATEGORY', 'PRODUCT')),
    CONSTRAINT chk_pharmacy_offer_dates CHECK (valid_until >= valid_from)
);

CREATE INDEX idx_pharmacy_offer_pharmacy ON pharmacy_offer (pharmacy_id, is_active);
CREATE INDEX idx_pharmacy_offer_valid_until ON pharmacy_offer (pharmacy_id, valid_until);

CREATE TABLE offer_redemption (
    id                     UUID PRIMARY KEY,
    offer_id               UUID NOT NULL REFERENCES pharmacy_offer (id),
    pharmacy_id            UUID NOT NULL,
    invoice_id             UUID NOT NULL REFERENCES invoice (id),
    customer_id            UUID,
    discount_amount_paise  BIGINT NOT NULL CHECK (discount_amount_paise >= 0),
    channel                VARCHAR(16) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_offer_redemption_channel CHECK (channel IN ('COUNTER', 'ONLINE'))
);

CREATE INDEX idx_offer_redemption_offer ON offer_redemption (offer_id);
CREATE INDEX idx_offer_redemption_invoice ON offer_redemption (invoice_id);

ALTER TABLE pos_cart
    ADD COLUMN applied_offer_id UUID REFERENCES pharmacy_offer (id);
