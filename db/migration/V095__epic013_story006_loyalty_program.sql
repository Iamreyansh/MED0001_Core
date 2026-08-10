-- EPIC-013 / STORY-006: loyalty program settings + redeem/expire/adjust tx types
-- Rollback:
--   DROP INDEX IF EXISTS idx_loyalty_transactions_earn_expiry;
--   DROP TABLE IF EXISTS loyalty_program_settings;
--   ALTER TABLE loyalty_transactions DROP COLUMN IF EXISTS adjusted_by;
--   ALTER TABLE loyalty_transactions DROP COLUMN IF EXISTS remaining_points;
--   ALTER TABLE loyalty_transactions DROP COLUMN IF EXISTS expires_at;
--   ALTER TABLE loyalty_transactions DROP CONSTRAINT IF EXISTS loyalty_transactions_points_sign_check;
--   ALTER TABLE loyalty_transactions DROP CONSTRAINT IF EXISTS loyalty_transactions_type_check;
--   ALTER TABLE loyalty_transactions ADD CONSTRAINT loyalty_transactions_type_check
--     CHECK (type IN ('EARN', 'REVERSE'));
--   ALTER TABLE loyalty_transactions ADD CONSTRAINT loyalty_transactions_points_sign_check
--     CHECK ((type = 'EARN' AND points > 0) OR (type = 'REVERSE' AND points < 0));
-- Notes: Keep EPIC-002 customer_loyalty + loyalty_transactions; extend types for
--   REDEEM/EXPIRE/ADJUST. FIFO expiry uses expires_at + remaining_points on EARN rows.
--   Singleton settings id fixed; money earn base is item_total (paise) at award time.

CREATE TABLE loyalty_program_settings (
    id                              UUID PRIMARY KEY,
    earn_rate_rs_per_point          INTEGER NOT NULL DEFAULT 100
        CHECK (earn_rate_rs_per_point > 0),
    redemption_rate_rs_per_point    NUMERIC(4, 2) NOT NULL DEFAULT 1.00
        CHECK (redemption_rate_rs_per_point > 0),
    tier_silver_pts                 INTEGER NOT NULL DEFAULT 12
        CHECK (tier_silver_pts > 0),
    tier_gold_pts                   INTEGER NOT NULL DEFAULT 50
        CHECK (tier_gold_pts > tier_silver_pts),
    tier_platinum_pts               INTEGER NOT NULL DEFAULT 120
        CHECK (tier_platinum_pts > tier_gold_pts),
    max_redemption_pct_per_order    INTEGER NOT NULL DEFAULT 20
        CHECK (max_redemption_pct_per_order > 0 AND max_redemption_pct_per_order <= 100),
    min_points_per_redemption       INTEGER NOT NULL DEFAULT 10
        CHECK (min_points_per_redemption > 0),
    points_expiry_days              INTEGER NOT NULL DEFAULT 365
        CHECK (points_expiry_days > 0),
    updated_by                      UUID REFERENCES admin_staff (id),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT loyalty_program_settings_singleton
        CHECK (id = '00000000-0000-4000-8000-000000000006'::uuid)
);

INSERT INTO loyalty_program_settings (
    id,
    earn_rate_rs_per_point,
    redemption_rate_rs_per_point,
    tier_silver_pts,
    tier_gold_pts,
    tier_platinum_pts,
    max_redemption_pct_per_order,
    min_points_per_redemption,
    points_expiry_days,
    updated_by,
    updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000006'::uuid,
    100,
    1.00,
    12,
    50,
    120,
    20,
    10,
    365,
    NULL,
    NOW()
);

ALTER TABLE loyalty_transactions
    DROP CONSTRAINT IF EXISTS loyalty_transactions_points_sign_check;

ALTER TABLE loyalty_transactions
    DROP CONSTRAINT IF EXISTS loyalty_transactions_type_check;

ALTER TABLE loyalty_transactions
    ADD CONSTRAINT loyalty_transactions_type_check
        CHECK (type IN ('EARN', 'REVERSE', 'REDEEM', 'EXPIRE', 'ADJUST'));

ALTER TABLE loyalty_transactions
    ADD CONSTRAINT loyalty_transactions_points_sign_check
        CHECK (
            (type = 'EARN' AND points > 0)
            OR (type = 'REVERSE' AND points < 0)
            OR (type = 'REDEEM' AND points < 0)
            OR (type = 'EXPIRE' AND points < 0)
            OR (type = 'ADJUST' AND points <> 0)
        );

ALTER TABLE loyalty_transactions
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL;

ALTER TABLE loyalty_transactions
    ADD COLUMN IF NOT EXISTS remaining_points INTEGER NULL
        CHECK (remaining_points IS NULL OR remaining_points >= 0);

ALTER TABLE loyalty_transactions
    ADD COLUMN IF NOT EXISTS adjusted_by UUID NULL REFERENCES admin_staff (id);

UPDATE loyalty_transactions
SET remaining_points = points,
    expires_at = created_at + INTERVAL '365 days'
WHERE type = 'EARN'
  AND remaining_points IS NULL;

CREATE INDEX IF NOT EXISTS idx_loyalty_transactions_earn_expiry
    ON loyalty_transactions (expires_at)
    WHERE type = 'EARN' AND remaining_points > 0 AND expires_at IS NOT NULL;
