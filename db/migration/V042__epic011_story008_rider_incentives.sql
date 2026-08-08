-- EPIC-011 / STORY-008: rider incentives & performance
-- Rollback:
--   DELETE FROM platform_pricing_config WHERE key IN (
--     'rider_base_pay_min_paise','rider_base_pay_max_paise',
--     'rider_base_pay_min_km','rider_base_pay_max_km',
--     'rider_streak_bonus_paise','rider_streak_days_required',
--     'rider_min_payout_paise','rider_acceptance_alert_threshold_pct');
--   DROP TABLE IF EXISTS rider_performance_badges;
--   DROP TABLE IF EXISTS rider_payouts;
--   ALTER TABLE riders DROP COLUMN IF EXISTS payout_carry_forward_paise;
--   ALTER TABLE riders DROP COLUMN IF EXISTS last_delivery_date;
--   ALTER TABLE riders DROP COLUMN IF EXISTS streak_bonus_pending;
--   ALTER TABLE rider_trip_earnings DROP COLUMN IF EXISTS delivery_date;
--   ALTER TABLE rider_trip_earnings DROP COLUMN IF EXISTS incentive_bonus_paise;
--   ALTER TABLE rider_trip_earnings DROP COLUMN IF EXISTS customer_rating;
--   ALTER TABLE rider_trip_earnings DROP COLUMN IF EXISTS distance_km;
-- Notes: Money BIGINT paise. Extends V037 rider_trip_earnings stub.
--        RazorpayX/Route + EPIC-015 incentive automation are stubs (outbox/config only).

INSERT INTO platform_pricing_config (key, value, description, updated_at)
VALUES
    ('rider_base_pay_min_paise', '1500', 'Base pay at/below min distance (₹15)', NOW()),
    ('rider_base_pay_max_paise', '2500', 'Base pay at/above max distance (₹25)', NOW()),
    ('rider_base_pay_min_km', '2.0', 'Distance (km) for min base pay', NOW()),
    ('rider_base_pay_max_km', '5.0', 'Distance (km) for max base pay', NOW()),
    ('rider_streak_bonus_paise', '10000', '7-day streak bonus (₹100)', NOW()),
    ('rider_streak_days_required', '7', 'Consecutive active days for streak bonus', NOW()),
    ('rider_min_payout_paise', '10000', 'Minimum weekly payout threshold (₹100)', NOW()),
    ('rider_acceptance_alert_threshold_pct', '70', 'Ops alert when acceptance rate below this %', NOW())
ON CONFLICT (key) DO NOTHING;

ALTER TABLE riders
    ADD COLUMN IF NOT EXISTS payout_carry_forward_paise BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_delivery_date DATE,
    ADD COLUMN IF NOT EXISTS streak_bonus_pending BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE rider_trip_earnings
    ADD COLUMN IF NOT EXISTS delivery_date DATE,
    ADD COLUMN IF NOT EXISTS incentive_bonus_paise BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS customer_rating SMALLINT,
    ADD COLUMN IF NOT EXISTS distance_km NUMERIC(6, 2);

UPDATE rider_trip_earnings
SET delivery_date = (created_at AT TIME ZONE 'Asia/Kolkata')::date
WHERE delivery_date IS NULL;

ALTER TABLE rider_trip_earnings
    ALTER COLUMN delivery_date SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rider_trip_earnings_rider_date
    ON rider_trip_earnings (rider_id, delivery_date);

CREATE TABLE rider_payouts (
    id                    UUID PRIMARY KEY,
    rider_id              UUID         NOT NULL REFERENCES riders (id),
    cycle_from            DATE         NOT NULL,
    cycle_to              DATE         NOT NULL,
    base_earnings_paise   BIGINT       NOT NULL DEFAULT 0,
    incentives_paise      BIGINT       NOT NULL DEFAULT 0,
    tips_paise            BIGINT       NOT NULL DEFAULT 0,
    streak_bonus_paise    BIGINT       NOT NULL DEFAULT 0,
    carry_forward_paise   BIGINT       NOT NULL DEFAULT 0,
    cod_deducted_paise    BIGINT       NOT NULL DEFAULT 0,
    net_payout_paise      BIGINT       NOT NULL DEFAULT 0,
    status                VARCHAR(40)  NOT NULL,
    hold_reason           TEXT,
    razorpay_payout_id    VARCHAR(100),
    payout_reference      VARCHAR(100),
    release_notes         TEXT,
    released_by           UUID,
    released_at           TIMESTAMPTZ,
    retry_count           SMALLINT     NOT NULL DEFAULT 0,
    next_retry_at         TIMESTAMPTZ,
    last_attempt_at       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uq_rider_payouts_cycle UNIQUE (rider_id, cycle_from, cycle_to),
    CONSTRAINT chk_rider_payouts_status CHECK (
        status IN (
            'PENDING',
            'HELD',
            'RELEASED',
            'FAILED',
            'BELOW_THRESHOLD_CARRIED_FORWARD'
        )
    ),
    CONSTRAINT chk_rider_payouts_retry CHECK (retry_count >= 0 AND retry_count <= 1)
);

CREATE INDEX idx_rider_payouts_rider_created
    ON rider_payouts (rider_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_rider_payouts_status_retry
    ON rider_payouts (status, next_retry_at)
    WHERE deleted_at IS NULL;

CREATE TABLE rider_performance_badges (
    id          UUID PRIMARY KEY,
    rider_id    UUID        NOT NULL REFERENCES riders (id),
    badge       VARCHAR(64) NOT NULL,
    earned_at   DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rider_badge UNIQUE (rider_id, badge)
);

CREATE INDEX idx_rider_performance_badges_rider
    ON rider_performance_badges (rider_id);
