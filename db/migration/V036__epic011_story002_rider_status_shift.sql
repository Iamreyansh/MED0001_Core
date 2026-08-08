-- EPIC-011 / STORY-002: rider availability & shift management
-- Rollback: DROP TABLE IF EXISTS rider_status_audit_log;
--           DROP TABLE IF EXISTS rider_shifts;
--           ALTER TABLE riders DROP CONSTRAINT IF EXISTS chk_riders_status;
--           ALTER TABLE riders ADD CONSTRAINT chk_riders_status
--             CHECK (status IN ('PENDING_KYC', 'ACTIVE', 'OFFLINE', 'BLOCKED'));
--           ALTER TABLE riders DROP COLUMN IF EXISTS last_location_at;
--           ALTER TABLE riders DROP COLUMN IF EXISTS current_zone_id;
--           DROP INDEX IF EXISTS idx_orders_rider_active;
-- Notes: expands riders.status with ONLINE/ON_TRIP (availability); money on shifts is BIGINT paise;
--        last_location_at stub for is_stale_gps until STORY-004 GPS posts.

ALTER TABLE riders DROP CONSTRAINT IF EXISTS chk_riders_status;

ALTER TABLE riders
    ADD COLUMN IF NOT EXISTS current_zone_id UUID REFERENCES zones (id),
    ADD COLUMN IF NOT EXISTS last_location_at TIMESTAMPTZ;

ALTER TABLE riders
    ADD CONSTRAINT chk_riders_status CHECK (
        status IN ('PENDING_KYC', 'ACTIVE', 'OFFLINE', 'ONLINE', 'ON_TRIP', 'BLOCKED')
    );

CREATE INDEX IF NOT EXISTS idx_riders_current_zone
    ON riders (current_zone_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orders_rider_active
    ON orders (rider_id)
    WHERE deleted_at IS NULL
      AND rider_id IS NOT NULL
      AND status NOT IN ('DELIVERED', 'CANCELLED');

CREATE TABLE rider_shifts (
    id                      UUID PRIMARY KEY,
    rider_id                UUID NOT NULL REFERENCES riders (id),
    zone_id                 UUID NOT NULL REFERENCES zones (id),
    shift_start             TIMESTAMPTZ NOT NULL,
    shift_end               TIMESTAMPTZ,
    duration_minutes        INTEGER,
    trips_in_shift          INTEGER NOT NULL DEFAULT 0,
    earnings_in_shift_paise BIGINT NOT NULL DEFAULT 0,
    force_closed_by         UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rider_shifts_rider_open
    ON rider_shifts (rider_id)
    WHERE shift_end IS NULL;

CREATE INDEX idx_rider_shifts_rider_start
    ON rider_shifts (rider_id, shift_start DESC);

CREATE TABLE rider_status_audit_log (
    id               UUID PRIMARY KEY,
    rider_id         UUID NOT NULL REFERENCES riders (id),
    changed_by       UUID NOT NULL,
    changed_by_role  VARCHAR(32) NOT NULL,
    from_status      VARCHAR(20) NOT NULL,
    to_status        VARCHAR(20) NOT NULL,
    reason           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_rider_status_audit_role CHECK (
        changed_by_role IN ('rider', 'admin_operations', 'admin_super')
    )
);

CREATE INDEX idx_rider_status_audit_rider
    ON rider_status_audit_log (rider_id, created_at DESC);
