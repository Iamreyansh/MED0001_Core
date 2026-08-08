-- EPIC-011 / STORY-003: order assignment engine
-- Rollback: DROP TABLE IF EXISTS rider_trip_earnings;
--           DROP TABLE IF EXISTS order_assignments;
--           DROP INDEX IF EXISTS idx_order_assignments_order_active;
--           DROP INDEX IF EXISTS idx_order_assignments_rider_active;
--           DROP INDEX IF EXISTS idx_order_assignments_pending_deadline;
-- Notes: OTP columns store SHA-256 hex (plaintext only in Redis, 30m TTL);
--        rider_trip_earnings is a stub ledger for STORY-008 earnings later;
--        money BIGINT paise; accept window 5 minutes (enforced in app).

CREATE TABLE order_assignments (
    id                   UUID PRIMARY KEY,
    order_id             UUID NOT NULL REFERENCES orders (id),
    rider_id             UUID NOT NULL REFERENCES riders (id),
    assignment_type      VARCHAR(16) NOT NULL,
    assigned_by          UUID,
    status               VARCHAR(32) NOT NULL,
    accept_deadline      TIMESTAMPTZ NOT NULL,
    accepted_at          TIMESTAMPTZ,
    pickup_confirmed_at  TIMESTAMPTZ,
    delivered_at         TIMESTAMPTZ,
    pickup_otp_hash      VARCHAR(64) NOT NULL,
    delivery_otp_hash    VARCHAR(64) NOT NULL,
    reassign_reason      VARCHAR(32),
    composite_score      NUMERIC(6, 2),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_order_assignments_type CHECK (
        assignment_type IN ('MANUAL', 'AUTO')
    ),
    CONSTRAINT chk_order_assignments_status CHECK (
        status IN (
            'PENDING_ACCEPTANCE',
            'ACCEPTED',
            'PICKED_UP',
            'DELIVERED',
            'REASSIGNED',
            'TIMED_OUT',
            'CANCELLED'
        )
    ),
    CONSTRAINT chk_order_assignments_reassign_reason CHECK (
        reassign_reason IS NULL
        OR reassign_reason IN ('RIDER_NO_SHOW', 'RIDER_OFFLINE', 'PERFORMANCE', 'OTHER')
    )
);

CREATE UNIQUE INDEX uq_order_assignments_order_active
    ON order_assignments (order_id)
    WHERE status IN ('PENDING_ACCEPTANCE', 'ACCEPTED', 'PICKED_UP');

CREATE INDEX idx_order_assignments_rider_active
    ON order_assignments (rider_id)
    WHERE status IN ('PENDING_ACCEPTANCE', 'ACCEPTED', 'PICKED_UP');

CREATE INDEX idx_order_assignments_pending_deadline
    ON order_assignments (accept_deadline)
    WHERE status = 'PENDING_ACCEPTANCE';

CREATE INDEX idx_order_assignments_order_created
    ON order_assignments (order_id, created_at DESC);

-- Stub earnings row on deliver (STORY-008 expands wallet/payouts).
CREATE TABLE rider_trip_earnings (
    id                UUID PRIMARY KEY,
    rider_id          UUID NOT NULL REFERENCES riders (id),
    order_id          UUID NOT NULL REFERENCES orders (id),
    assignment_id     UUID NOT NULL REFERENCES order_assignments (id),
    base_pay_paise    BIGINT NOT NULL,
    tip_paise         BIGINT NOT NULL DEFAULT 0,
    total_paise       BIGINT NOT NULL,
    on_time           BOOLEAN NOT NULL,
    delivery_minutes  INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rider_trip_earnings_assignment UNIQUE (assignment_id)
);

CREATE INDEX idx_rider_trip_earnings_rider_created
    ON rider_trip_earnings (rider_id, created_at DESC);
