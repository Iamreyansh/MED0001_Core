-- EPIC-013 / STORY-005: referral program settings + share analytics
-- Rollback:
--   DROP TABLE IF EXISTS referral_share_events;
--   ALTER TABLE referral_events DROP COLUMN IF EXISTS referee_reward_amount_paise;
--   DROP TABLE IF EXISTS referral_program_settings;
-- Notes: money as BIGINT paise (API exposes *_rs). Singleton settings row.
--   Keep EPIC-002 MED**** codes + referral_events statuses (PENDING/REWARDED/CANCELLED).
--   referee_reward_amount_paise snapshots referee credit at apply time (referrer stays in reward_amount_paise).

CREATE TABLE referral_program_settings (
    id                          UUID PRIMARY KEY,
    reward_for_referrer_paise   BIGINT NOT NULL CHECK (reward_for_referrer_paise > 0),
    reward_for_referee_paise    BIGINT NOT NULL CHECK (reward_for_referee_paise > 0),
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    reward_expiry_days          INTEGER NOT NULL DEFAULT 365 CHECK (reward_expiry_days > 0),
    conditions                  TEXT NULL,
    updated_by                  UUID REFERENCES admin_staff (id),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT referral_program_settings_singleton
        CHECK (id = '00000000-0000-4000-8000-000000000013'::uuid)
);

INSERT INTO referral_program_settings (
    id,
    reward_for_referrer_paise,
    reward_for_referee_paise,
    is_active,
    reward_expiry_days,
    conditions,
    updated_by,
    updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000013'::uuid,
    10000,
    10000,
    TRUE,
    365,
    'Reward credited after referee''s first DELIVERED order. One code per customer.',
    NULL,
    NOW()
);

ALTER TABLE referral_events
    ADD COLUMN IF NOT EXISTS referee_reward_amount_paise BIGINT;

UPDATE referral_events
SET referee_reward_amount_paise = reward_amount_paise
WHERE referee_reward_amount_paise IS NULL;

ALTER TABLE referral_events
    ALTER COLUMN referee_reward_amount_paise SET DEFAULT 10000;

ALTER TABLE referral_events
    ALTER COLUMN referee_reward_amount_paise SET NOT NULL;

ALTER TABLE referral_events
    DROP CONSTRAINT IF EXISTS referral_events_referee_reward_positive;

ALTER TABLE referral_events
    ADD CONSTRAINT referral_events_referee_reward_positive
        CHECK (referee_reward_amount_paise > 0);

CREATE TABLE referral_share_events (
    id           UUID PRIMARY KEY,
    customer_id  UUID NOT NULL REFERENCES customers (id),
    channel      VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_referral_share_events_customer_created
    ON referral_share_events (customer_id, created_at DESC);

CREATE INDEX idx_referral_share_events_channel
    ON referral_share_events (channel);
