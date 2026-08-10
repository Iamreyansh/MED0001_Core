-- EPIC-015 / STORY-002: support disputes + events, daily DSP- id seq
-- Rollback:
--   DROP INDEX IF EXISTS idx_support_disputes_status;
--   DROP INDEX IF EXISTS idx_support_disputes_customer;
--   DROP INDEX IF EXISTS idx_support_disputes_resolution_sla;
--   DROP TABLE IF EXISTS support_dispute_events;
--   DROP TABLE IF EXISTS support_disputes;
--   DROP TABLE IF EXISTS support_dispute_id_seq;
-- Notes: refund amounts in paise (BIGINT). Auto-process cap Rs 200 = 20000 paise.
--   Leave EPIC-010 order_dispute alone — these are support-domain tables.

CREATE TABLE support_dispute_id_seq (
    day_key     DATE PRIMARY KEY,
    last_seq    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE support_disputes (
    id                          UUID PRIMARY KEY,
    dispute_id                  VARCHAR(22) NOT NULL,
    order_id                    UUID NOT NULL,
    customer_id                 UUID NOT NULL,
    dispute_type                VARCHAR(30) NOT NULL,
    description                 TEXT NOT NULL,
    evidence_urls               TEXT[] NOT NULL DEFAULT '{}',
    status                      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    liable_party                VARCHAR(20),
    refund_amount_paise         BIGINT,
    refund_to                   VARCHAR(10),
    resolution_notes            TEXT,
    rejection_reason            TEXT,
    investigated_by             UUID,
    resolved_at                 TIMESTAMPTZ,
    resolution_sla_at           TIMESTAMPTZ NOT NULL,
    recommended_liable_party    VARCHAR(20) NOT NULL,
    auto_processed              BOOLEAN NOT NULL DEFAULT FALSE,
    refund_txn_id               VARCHAR(64),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at                  TIMESTAMPTZ,
    CONSTRAINT uq_support_disputes_dispute_id UNIQUE (dispute_id),
    CONSTRAINT uq_support_disputes_order_id UNIQUE (order_id),
    CONSTRAINT chk_support_disputes_type CHECK (dispute_type IN (
        'WRONG_ITEMS', 'MISSING_ITEMS', 'DAMAGED', 'NOT_DELIVERED',
        'EXPIRED_MEDICINE', 'QUALITY', 'OVERCHARGED'
    )),
    CONSTRAINT chk_support_disputes_status CHECK (status IN (
        'OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED'
    )),
    CONSTRAINT chk_support_disputes_liable CHECK (
        liable_party IS NULL OR liable_party IN ('PHARMACY', 'RIDER', 'PLATFORM', 'CUSTOMER')
    ),
    CONSTRAINT chk_support_disputes_refund_to CHECK (
        refund_to IS NULL OR refund_to IN ('SOURCE', 'WALLET')
    ),
    CONSTRAINT chk_support_disputes_recommended CHECK (
        recommended_liable_party IN ('PHARMACY', 'RIDER', 'PLATFORM', 'CUSTOMER')
    )
);

CREATE INDEX idx_support_disputes_status
    ON support_disputes (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_support_disputes_customer
    ON support_disputes (customer_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_support_disputes_resolution_sla
    ON support_disputes (resolution_sla_at)
    WHERE deleted_at IS NULL AND status IN ('OPEN', 'INVESTIGATING');

CREATE TABLE support_dispute_events (
    id              UUID PRIMARY KEY,
    dispute_id      UUID NOT NULL REFERENCES support_disputes (id),
    event_type      VARCHAR(40) NOT NULL,
    actor_id        UUID,
    actor_name      VARCHAR(100) NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_support_dispute_events_dispute
    ON support_dispute_events (dispute_id, created_at);
