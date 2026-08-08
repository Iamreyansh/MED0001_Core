-- EPIC-010 / STORY-005: order status lifecycle + OTP hash + SLA columns
-- Rollback: DROP TABLE IF EXISTS order_status_event;
--           ALTER TABLE orders DROP COLUMN IF EXISTS accepted_at, delivered_at, sla_deadline,
--             sla_breached, rider_assigned_at, otp_verified_at, ready_for_pickup_at,
--             rider_escalation_at, cancel_reason;
--           ALTER TABLE orders RENAME COLUMN delivery_otp_hash TO delivery_otp;
--           ALTER TABLE orders ALTER COLUMN delivery_otp TYPE VARCHAR(4);
-- Notes: OTP stored bcrypt-hashed; money unchanged (paise); status events append-only.

ALTER TABLE orders RENAME COLUMN delivery_otp TO delivery_otp_hash;
ALTER TABLE orders ALTER COLUMN delivery_otp_hash TYPE VARCHAR(100);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS sla_deadline TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS sla_breached BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS rider_assigned_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS otp_verified_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS ready_for_pickup_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS rider_escalation_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(64) NULL;

CREATE TABLE order_status_event (
    id           UUID PRIMARY KEY,
    order_id     UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status  VARCHAR(30) NOT NULL,
    to_status    VARCHAR(30) NOT NULL,
    actor_type   VARCHAR(20) NOT NULL,
    actor_id     UUID NULL,
    notes        TEXT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_order_status_event_actor
        CHECK (actor_type IN ('SYSTEM', 'CUSTOMER', 'PHARMACY', 'RIDER', 'ADMIN'))
);

CREATE INDEX idx_order_status_event_order_created
    ON order_status_event (order_id, created_at ASC);

CREATE INDEX idx_orders_pending_acceptance_confirmed
    ON orders (confirmed_at)
    WHERE deleted_at IS NULL AND status = 'PENDING_ACCEPTANCE';

CREATE INDEX idx_orders_ready_no_rider
    ON orders (ready_for_pickup_at)
    WHERE deleted_at IS NULL
      AND status = 'READY_FOR_PICKUP'
      AND rider_id IS NULL
      AND rider_escalation_at IS NULL;
