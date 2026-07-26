-- EPIC-002 / STORY-004: Idempotency-Key on saved payment method creates
-- Rollback:
--   DROP INDEX IF EXISTS idx_saved_payment_methods_idempotency;
--   ALTER TABLE saved_payment_methods DROP COLUMN IF EXISTS idempotency_key;
-- Notes: optional Idempotency-Key for save UPI/card mutators; unique among active rows.

ALTER TABLE saved_payment_methods
    ADD COLUMN idempotency_key VARCHAR(255) NULL;

CREATE UNIQUE INDEX idx_saved_payment_methods_idempotency
    ON saved_payment_methods (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted_at IS NULL;
