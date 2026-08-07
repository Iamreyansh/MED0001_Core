-- EPIC-004 / STORY-003: commission history + weekly settlements
-- Rollback: DROP TABLE IF EXISTS settlement;
--           DROP TABLE IF EXISTS commission_history;
-- Notes: money in paise (BIGINT); settlement status lifecycle PENDING_RELEASE → RELEASED → PAID.

CREATE TABLE IF NOT EXISTS commission_history (
    id                       UUID PRIMARY KEY,
    pharmacy_id              UUID NOT NULL REFERENCES pharmacies (id),
    previous_commission_pct  NUMERIC(5, 2) NOT NULL,
    new_commission_pct       NUMERIC(5, 2) NOT NULL,
    effective_from           DATE NOT NULL,
    reason                   TEXT NOT NULL,
    notes                    TEXT,
    changed_by               UUID NOT NULL,
    changed_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_at               TIMESTAMPTZ,
    deleted_at               TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_commission_history_pharmacy_pending
    ON commission_history (pharmacy_id)
    WHERE applied_at IS NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_commission_history_effective_from
    ON commission_history (effective_from)
    WHERE applied_at IS NULL AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS settlement (
    id                       UUID PRIMARY KEY,
    pharmacy_id              UUID NOT NULL REFERENCES pharmacies (id),
    period_start             DATE NOT NULL,
    period_end               DATE NOT NULL,
    gmv_paise                BIGINT NOT NULL,
    commission_pct           NUMERIC(5, 2) NOT NULL,
    commission_earned_paise  BIGINT NOT NULL,
    tcs_rate_pct             NUMERIC(5, 2) NOT NULL DEFAULT 1.00,
    tcs_deducted_paise       BIGINT NOT NULL,
    net_paid_paise           BIGINT NOT NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING_RELEASE',
    hold_reason              TEXT,
    released_by              UUID,
    released_at              TIMESTAMPTZ,
    paid_at                  TIMESTAMPTZ,
    razorpayx_payout_id      VARCHAR(100),
    utr_number               VARCHAR(50),
    receipt_url              TEXT,
    release_idempotency_key  VARCHAR(128),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMPTZ,
    CONSTRAINT chk_settlement_status CHECK (
        status IN ('PENDING_RELEASE', 'RELEASED', 'PAID', 'HELD', 'FAILED')
    ),
    CONSTRAINT uq_settlement_pharmacy_period UNIQUE (pharmacy_id, period_start, period_end)
);

CREATE INDEX IF NOT EXISTS idx_settlement_pharmacy_status
    ON settlement (pharmacy_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_settlement_period
    ON settlement (pharmacy_id, period_start DESC)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_settlement_release_idempotency
    ON settlement (release_idempotency_key)
    WHERE release_idempotency_key IS NOT NULL AND deleted_at IS NULL;
