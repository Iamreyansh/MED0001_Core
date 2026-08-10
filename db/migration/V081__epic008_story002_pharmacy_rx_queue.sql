-- EPIC-008 / STORY-002: pharmacy prescription review queue
-- Rollback: DROP INDEX IF EXISTS idx_pharmacy_rx_queue_kpi_dispensed;
--           DROP INDEX IF EXISTS idx_pharmacy_rx_queue_overdue;
--           DROP INDEX IF EXISTS idx_pharmacy_rx_queue_pharmacy_status;
--           DROP TABLE IF EXISTS pharmacy_rx_queue;
-- Notes: SLA deadline is received_at + 2h (computed in app). overdue_notified_at
--        ensures WhatsApp owner alert fires once. approved_medicines JSONB stores
--        [{name, quantity, price}] with price in rupees for API parity.

CREATE TABLE pharmacy_rx_queue (
    id                      UUID PRIMARY KEY,
    rx_id                   UUID NOT NULL REFERENCES prescription (id),
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    order_id                UUID NULL REFERENCES orders (id),
    received_at             TIMESTAMPTZ NOT NULL,
    status                  VARCHAR(30) NOT NULL,
    approved_medicines      JSONB NULL,
    approved_by             UUID NULL,
    approved_at             TIMESTAMPTZ NULL,
    rejected_reason         VARCHAR(40) NULL,
    rejected_custom_message VARCHAR(300) NULL,
    rejected_by             UUID NULL,
    rejected_at             TIMESTAMPTZ NULL,
    dispensed_by            UUID NULL,
    dispensed_at            TIMESTAMPTZ NULL,
    notes                   VARCHAR(500) NULL,
    duplicate_warning       BOOLEAN NOT NULL DEFAULT FALSE,
    overdue_notified_at     TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMPTZ NULL,
    CONSTRAINT uq_pharmacy_rx_queue_rx_pharmacy UNIQUE (rx_id, pharmacy_id),
    CONSTRAINT chk_pharmacy_rx_queue_status CHECK (status IN (
        'PENDING_REVIEW',
        'APPROVED',
        'REJECTED',
        'DISPENSED'
    )),
    CONSTRAINT chk_pharmacy_rx_queue_reject_reason CHECK (
        rejected_reason IS NULL OR rejected_reason IN (
            'ILLEGIBLE',
            'UNVERIFIED_PRESCRIBER',
            'EXPIRED',
            'NOT_STOCKED',
            'INVALID'
        )
    )
);

CREATE INDEX idx_pharmacy_rx_queue_pharmacy_status
    ON pharmacy_rx_queue (pharmacy_id, status, received_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pharmacy_rx_queue_overdue
    ON pharmacy_rx_queue (pharmacy_id, received_at)
    WHERE deleted_at IS NULL AND status = 'PENDING_REVIEW';

CREATE INDEX idx_pharmacy_rx_queue_kpi_dispensed
    ON pharmacy_rx_queue (pharmacy_id, dispensed_at)
    WHERE deleted_at IS NULL AND status = 'DISPENSED';
