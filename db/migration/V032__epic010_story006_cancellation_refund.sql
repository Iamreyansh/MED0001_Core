-- EPIC-010 / STORY-006: order cancellation + refund
-- Rollback: DROP TABLE IF EXISTS refund;
--           DROP TABLE IF EXISTS order_cancellation;
-- Notes: money BIGINT paise; one cancellation per order; refund statuses INITIATED|PROCESSED|FAILED.

CREATE TABLE order_cancellation (
    id                  UUID PRIMARY KEY,
    order_id            UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    cancelled_by_type   VARCHAR(20) NOT NULL,
    cancelled_by_id     UUID NULL,
    reason              VARCHAR(300) NOT NULL,
    cancelled_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_order_cancellation_order UNIQUE (order_id),
    CONSTRAINT chk_order_cancellation_by_type
        CHECK (cancelled_by_type IN ('CUSTOMER', 'PHARMACY', 'ADMIN', 'SYSTEM'))
);

CREATE TABLE refund (
    id                      UUID PRIMARY KEY,
    order_id                UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    amount_paise            BIGINT NOT NULL,
    refund_to               VARCHAR(20) NOT NULL,
    reason                  VARCHAR(300) NOT NULL,
    notes                   VARCHAR(500) NULL,
    status                  VARCHAR(20) NOT NULL,
    issued_by               UUID NULL,
    issued_by_type          VARCHAR(20) NOT NULL,
    razorpay_refund_id      VARCHAR(64) NULL,
    wallet_transaction_id   UUID NULL,
    processed_at            TIMESTAMPTZ NULL,
    failed_reason           VARCHAR(300) NULL,
    idempotency_key         VARCHAR(128) NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_refund_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_refund_to CHECK (refund_to IN ('SOURCE', 'WALLET')),
    CONSTRAINT chk_refund_status CHECK (status IN ('INITIATED', 'PROCESSED', 'FAILED')),
    CONSTRAINT chk_refund_issued_by_type
        CHECK (issued_by_type IN ('ADMIN', 'SYSTEM', 'PHARMACY')),
    CONSTRAINT uq_refund_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_refund_order_created ON refund (order_id, created_at DESC);
CREATE INDEX idx_refund_razorpay ON refund (razorpay_refund_id)
    WHERE razorpay_refund_id IS NOT NULL;
