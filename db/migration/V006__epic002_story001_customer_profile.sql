-- EPIC-002 / STORY-001: customer profile, flags, deletion grace, denormalised stats
-- Rollback:
--   DROP INDEX IF EXISTS idx_customer_admin_notifications_customer_created;
--   DROP TABLE IF EXISTS customer_admin_notifications;
--   DROP INDEX IF EXISTS idx_customer_segment_changes_customer_id;
--   DROP TABLE IF EXISTS customer_segment_changes;
--   DROP INDEX IF EXISTS idx_customers_deletion_requested_at;
--   DROP INDEX IF EXISTS idx_customers_is_flagged;
--   DROP INDEX IF EXISTS idx_customers_segment;
--   ALTER TABLE customers
--     DROP COLUMN IF EXISTS city,
--     DROP COLUMN IF EXISTS is_flagged,
--     DROP COLUMN IF EXISTS flag_reason,
--     DROP COLUMN IF EXISTS flag_note,
--     DROP COLUMN IF EXISTS flagged_by,
--     DROP COLUMN IF EXISTS flagged_at,
--     DROP COLUMN IF EXISTS total_orders,
--     DROP COLUMN IF EXISTS total_ltv_paise,
--     DROP COLUMN IF EXISTS cancel_rate,
--     DROP COLUMN IF EXISTS dispute_count,
--     DROP COLUMN IF EXISTS last_order_at,
--     DROP COLUMN IF EXISTS deletion_requested_at,
--     DROP COLUMN IF EXISTS deletion_reason;
--   ALTER TABLE customers
--     ALTER COLUMN preferred_language DROP NOT NULL,
--     ALTER COLUMN preferred_language DROP DEFAULT,
--     ALTER COLUMN segment DROP NOT NULL,
--     ALTER COLUMN segment DROP DEFAULT,
--     ALTER COLUMN phone TYPE VARCHAR(15),
--     ALTER COLUMN name TYPE VARCHAR(255),
--     ALTER COLUMN avatar_url TYPE VARCHAR(1024);
-- Notes: money in paise; city denormalised until STORY-002 addresses; phone widened for anonymised hashes.
-- name/avatar_url narrowing is safe on empty/OTP-created rows (name/avatar null); fails if any row exceeds new width.

ALTER TABLE customers
    ALTER COLUMN phone TYPE VARCHAR(64),
    ALTER COLUMN name TYPE VARCHAR(100),
    ALTER COLUMN avatar_url TYPE VARCHAR(512),
    ALTER COLUMN preferred_language SET DEFAULT 'en',
    ALTER COLUMN segment SET DEFAULT 'NEW';

UPDATE customers
SET preferred_language = 'en'
WHERE preferred_language IS NULL;

UPDATE customers
SET segment = 'NEW'
WHERE segment IS NULL;

ALTER TABLE customers
    ALTER COLUMN preferred_language SET NOT NULL,
    ALTER COLUMN segment SET NOT NULL;

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS city VARCHAR(100),
    ADD COLUMN IF NOT EXISTS is_flagged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS flag_reason VARCHAR(30),
    ADD COLUMN IF NOT EXISTS flag_note TEXT,
    ADD COLUMN IF NOT EXISTS flagged_by UUID,
    ADD COLUMN IF NOT EXISTS flagged_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS total_orders INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_ltv_paise BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cancel_rate NUMERIC(5, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dispute_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_order_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deletion_reason VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_customers_segment ON customers (segment)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_customers_is_flagged ON customers (is_flagged)
    WHERE deleted_at IS NULL AND is_flagged = TRUE;
CREATE INDEX IF NOT EXISTS idx_customers_deletion_requested_at ON customers (deletion_requested_at)
    WHERE deleted_at IS NULL AND deletion_requested_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_customers_city_lower ON customers (LOWER(city))
    WHERE deleted_at IS NULL AND city IS NOT NULL;

CREATE TABLE customer_segment_changes (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers (id),
    from_segment VARCHAR(10) NOT NULL,
    to_segment VARCHAR(10) NOT NULL,
    total_orders INTEGER NOT NULL,
    total_ltv_paise BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_segment_changes_customer_id ON customer_segment_changes (customer_id);

-- Rate-limit audit for admin notify (3 / 24h); also used when Redis limiter is cold-started
CREATE TABLE customer_admin_notifications (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers (id),
    channel VARCHAR(10) NOT NULL,
    title VARCHAR(65),
    body VARCHAR(255) NOT NULL,
    deep_link VARCHAR(512),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_admin_notifications_customer_created
    ON customer_admin_notifications (customer_id, created_at DESC);
