-- EPIC-012 / STORY-001: payment processing (UPI / Card / COD)
-- Rollback:
--   DROP TABLE IF EXISTS financial_ledger;
--   DROP TABLE IF EXISTS payment;
-- Notes: money as BIGINT paise (platform rule); story DECIMAL fields map to *_paise columns.
--        financial_ledger is append-only writer surface for capture; full query/export is STORY-008.

CREATE TABLE payment (
    id                      UUID PRIMARY KEY,
    order_id                UUID NOT NULL,
    customer_id             UUID NOT NULL,
    amount_paise            BIGINT NOT NULL,
    wallet_portion_paise    BIGINT NOT NULL DEFAULT 0,
    gateway_portion_paise   BIGINT NOT NULL DEFAULT 0,
    currency                CHAR(3) NOT NULL DEFAULT 'INR',
    method                  VARCHAR(20) NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    razorpay_order_id       VARCHAR(100) NULL,
    razorpay_payment_id     VARCHAR(100) NULL,
    razorpay_signature      VARCHAR(255) NULL,
    gateway_fee_paise       BIGINT NULL,
    gateway_response        JSONB NULL,
    webhook_events          JSONB NULL,
    captured_at             TIMESTAMPTZ NULL,
    failed_at               TIMESTAMPTZ NULL,
    failure_reason          TEXT NULL,
    idempotency_key         VARCHAR(100) NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payment_order_id UNIQUE (order_id),
    CONSTRAINT chk_payment_method
        CHECK (method IN ('UPI', 'CARD', 'COD', 'WALLET_ONLY')),
    CONSTRAINT chk_payment_status
        CHECK (status IN (
            'PENDING',
            'CAPTURED',
            'FAILED',
            'REFUNDED',
            'PENDING_COD',
            'COLLECTED_COD'
        )),
    CONSTRAINT chk_payment_amounts_nonneg
        CHECK (
            amount_paise >= 0
            AND wallet_portion_paise >= 0
            AND gateway_portion_paise >= 0
            AND (gateway_fee_paise IS NULL OR gateway_fee_paise >= 0)
        )
);

CREATE INDEX idx_payment_customer_created
    ON payment (customer_id, created_at DESC);

CREATE INDEX idx_payment_razorpay_order
    ON payment (razorpay_order_id)
    WHERE razorpay_order_id IS NOT NULL;

CREATE INDEX idx_payment_razorpay_payment
    ON payment (razorpay_payment_id)
    WHERE razorpay_payment_id IS NOT NULL;

CREATE TABLE financial_ledger (
    id              UUID PRIMARY KEY,
    entry_type      VARCHAR(40) NOT NULL,
    reference_id    UUID NOT NULL,
    reference_type  VARCHAR(40) NOT NULL,
    credit_paise    BIGINT NOT NULL DEFAULT 0,
    debit_paise     BIGINT NOT NULL DEFAULT 0,
    description     TEXT NULL,
    metadata        JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_financial_ledger_credit_or_debit
        CHECK (
            (credit_paise > 0 AND debit_paise = 0)
            OR (debit_paise > 0 AND credit_paise = 0)
        )
);

CREATE INDEX idx_financial_ledger_reference
    ON financial_ledger (reference_type, reference_id, created_at);
