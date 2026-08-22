-- Production integration: payment-method guard, KYC alert dispatch
-- Rollback:
--   ALTER TABLE orders DROP COLUMN IF EXISTS saved_payment_method_id;
--   ALTER TABLE kyc_expiry_alerts DROP COLUMN IF EXISTS sent_at;
-- Notes: per-method in-use guard (R9); expiry-alert idempotency.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS saved_payment_method_id UUID;

CREATE INDEX IF NOT EXISTS idx_orders_saved_payment_method_active
    ON orders (saved_payment_method_id)
    WHERE deleted_at IS NULL
      AND saved_payment_method_id IS NOT NULL
      AND status NOT IN ('DELIVERED', 'CANCELLED');

ALTER TABLE kyc_expiry_alerts
    ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ;
