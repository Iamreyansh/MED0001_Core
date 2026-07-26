-- EPIC-002 / STORY-005: loyalty points + referral programme
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_customers_create_loyalty_referral ON customers;
--   DROP FUNCTION IF EXISTS create_loyalty_referral_for_customer();
--   DROP FUNCTION IF EXISTS generate_referral_code();
--   DROP INDEX IF EXISTS idx_referral_events_referrer_status;
--   DROP INDEX IF EXISTS idx_referral_events_referee;
--   DROP INDEX IF EXISTS idx_loyalty_transactions_reference_id;
--   DROP INDEX IF EXISTS idx_loyalty_transactions_customer_created;
--   DROP TABLE IF EXISTS referral_events;
--   DROP TABLE IF EXISTS customer_referrals;
--   DROP TABLE IF EXISTS loyalty_transactions;
--   DROP TABLE IF EXISTS customer_loyalty;
--   ALTER TABLE wallet_transactions DROP CONSTRAINT IF EXISTS wallet_transactions_reason_check;
--   ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_reason_check
--     CHECK (reason IN ('REFUND', 'GOODWILL', 'PROMOTIONAL', 'ORDER_PAYMENT', 'EXPIRY'));
-- Notes: money stored as paise (BIGINT). Tier ratchet uses points_earned_lifetime.
--   first_order_id is UUID without FK until EPIC-010 creates orders.
--   Trigger auto-creates loyalty + referral rows (MED + 4 base-36) on customer insert.
--   CHECKs enforce EARN>0 / REVERSE<0 loyalty points and reject self-referral rows at the DB.

ALTER TABLE wallet_transactions
    DROP CONSTRAINT IF EXISTS wallet_transactions_reason_check;

ALTER TABLE wallet_transactions
    ADD CONSTRAINT wallet_transactions_reason_check
        CHECK (reason IN (
            'REFUND', 'GOODWILL', 'PROMOTIONAL', 'ORDER_PAYMENT', 'EXPIRY', 'REFERRAL'
        ));

CREATE TABLE customer_loyalty (
    id                       UUID PRIMARY KEY,
    customer_id              UUID NOT NULL UNIQUE REFERENCES customers (id),
    tier                     VARCHAR(10) NOT NULL DEFAULT 'NONE'
        CHECK (tier IN ('NONE', 'SILVER', 'GOLD', 'PLATINUM')),
    points_balance           INTEGER NOT NULL DEFAULT 0 CHECK (points_balance >= 0),
    points_earned_lifetime   INTEGER NOT NULL DEFAULT 0 CHECK (points_earned_lifetime >= 0),
    updated_at               TIMESTAMPTZ NOT NULL
);

CREATE TABLE loyalty_transactions (
    id                     UUID PRIMARY KEY,
    customer_id            UUID NOT NULL REFERENCES customers (id),
    type                   VARCHAR(10) NOT NULL CHECK (type IN ('EARN', 'REVERSE')),
    points                 INTEGER NOT NULL,
    points_balance_after   INTEGER NOT NULL CHECK (points_balance_after >= 0),
    CONSTRAINT loyalty_transactions_points_sign_check CHECK (
        (type = 'EARN' AND points > 0) OR (type = 'REVERSE' AND points < 0)
    ),
    description            VARCHAR(255) NOT NULL,
    reference_id           UUID NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loyalty_transactions_customer_created
    ON loyalty_transactions (customer_id, created_at DESC);

CREATE INDEX idx_loyalty_transactions_reference_id
    ON loyalty_transactions (reference_id)
    WHERE reference_id IS NOT NULL;

-- One EARN (and one REVERSE) per order reference.
CREATE UNIQUE INDEX idx_loyalty_transactions_earn_reference
    ON loyalty_transactions (reference_id)
    WHERE type = 'EARN' AND reference_id IS NOT NULL;

CREATE UNIQUE INDEX idx_loyalty_transactions_reverse_reference
    ON loyalty_transactions (reference_id)
    WHERE type = 'REVERSE' AND reference_id IS NOT NULL;

CREATE TABLE customer_referrals (
    id                    UUID PRIMARY KEY,
    customer_id           UUID NOT NULL UNIQUE REFERENCES customers (id),
    referral_code         VARCHAR(10) NOT NULL UNIQUE,
    total_referrals       INTEGER NOT NULL DEFAULT 0 CHECK (total_referrals >= 0),
    converted_referrals   INTEGER NOT NULL DEFAULT 0 CHECK (converted_referrals >= 0),
    total_earned_paise    BIGINT NOT NULL DEFAULT 0 CHECK (total_earned_paise >= 0),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE referral_events (
    id                      UUID PRIMARY KEY,
    referee_customer_id     UUID NOT NULL REFERENCES customers (id),
    referrer_customer_id    UUID NOT NULL REFERENCES customers (id),
    referral_code           VARCHAR(10) NOT NULL,
    status                  VARCHAR(15) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'REWARDED', 'CANCELLED')),
    first_order_id          UUID NULL,
    reward_amount_paise     BIGINT NOT NULL DEFAULT 10000 CHECK (reward_amount_paise > 0),
    referee_rewarded_at     TIMESTAMPTZ NULL,
    referrer_rewarded_at    TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT referral_events_no_self_referral
        CHECK (referee_customer_id <> referrer_customer_id)
);

-- At most one applied referral per referee.
CREATE UNIQUE INDEX idx_referral_events_referee
    ON referral_events (referee_customer_id);

CREATE INDEX idx_referral_events_referrer_status
    ON referral_events (referrer_customer_id, status);

CREATE OR REPLACE FUNCTION generate_referral_code()
RETURNS VARCHAR(10) AS $$
DECLARE
    alphabet CONSTANT TEXT := '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
    candidate VARCHAR(10);
    i INT;
    idx INT;
BEGIN
    FOR i IN 1..32 LOOP
        candidate := 'MED';
        FOR idx IN 1..4 LOOP
            candidate := candidate || substr(alphabet, 1 + floor(random() * 36)::int, 1);
        END LOOP;
        IF NOT EXISTS (
            SELECT 1 FROM customer_referrals WHERE referral_code = candidate
        ) THEN
            RETURN candidate;
        END IF;
    END LOOP;
    RAISE EXCEPTION 'unable to generate unique referral code';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION create_loyalty_referral_for_customer()
RETURNS TRIGGER AS $$
DECLARE
    now_ts TIMESTAMPTZ := NOW();
BEGIN
    INSERT INTO customer_loyalty (
        id, customer_id, tier, points_balance, points_earned_lifetime, updated_at
    ) VALUES (
        gen_random_uuid(), NEW.id, 'NONE',
        GREATEST(COALESCE(NEW.loyalty_points, 0), 0),
        GREATEST(COALESCE(NEW.loyalty_points, 0), 0),
        now_ts
    )
    ON CONFLICT (customer_id) DO NOTHING;

    INSERT INTO customer_referrals (
        id, customer_id, referral_code, total_referrals, converted_referrals,
        total_earned_paise, created_at
    ) VALUES (
        gen_random_uuid(), NEW.id, generate_referral_code(), 0, 0, 0, now_ts
    )
    ON CONFLICT (customer_id) DO NOTHING;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_customers_create_loyalty_referral
    AFTER INSERT ON customers
    FOR EACH ROW
    EXECUTE FUNCTION create_loyalty_referral_for_customer();

-- Backfill existing customers.
INSERT INTO customer_loyalty (
    id, customer_id, tier, points_balance, points_earned_lifetime, updated_at
)
SELECT
    gen_random_uuid(),
    c.id,
    CASE
        WHEN c.loyalty_points >= 120 THEN 'PLATINUM'
        WHEN c.loyalty_points >= 50 THEN 'GOLD'
        WHEN c.loyalty_points >= 12 THEN 'SILVER'
        ELSE 'NONE'
    END,
    GREATEST(c.loyalty_points, 0),
    GREATEST(c.loyalty_points, 0),
    NOW()
FROM customers c
WHERE NOT EXISTS (
    SELECT 1 FROM customer_loyalty cl WHERE cl.customer_id = c.id
);

INSERT INTO customer_referrals (
    id, customer_id, referral_code, total_referrals, converted_referrals,
    total_earned_paise, created_at
)
SELECT
    gen_random_uuid(),
    c.id,
    generate_referral_code(),
    0,
    0,
    0,
    COALESCE(c.created_at, NOW())
FROM customers c
WHERE NOT EXISTS (
    SELECT 1 FROM customer_referrals cr WHERE cr.customer_id = c.id
);
