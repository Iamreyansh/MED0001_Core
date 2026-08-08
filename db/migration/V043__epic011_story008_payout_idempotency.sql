-- EPIC-011 / STORY-008: Idempotency-Key on admin rider payout release
-- Rollback:
--   DROP INDEX IF EXISTS uq_rider_payouts_release_idempotency;
--   ALTER TABLE rider_payouts DROP COLUMN IF EXISTS release_idempotency_key;
-- Notes: payment-like mutator claim/replay (pharmacy settlement pattern).

ALTER TABLE rider_payouts
    ADD COLUMN IF NOT EXISTS release_idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_rider_payouts_release_idempotency
    ON rider_payouts (release_idempotency_key)
    WHERE release_idempotency_key IS NOT NULL AND deleted_at IS NULL;
