-- EPIC-002 / STORY-004: saved UPI + card payment methods
-- Rollback:
--   DROP INDEX IF EXISTS idx_saved_payment_methods_one_default;
--   DROP INDEX IF EXISTS idx_saved_payment_methods_customer_type;
--   DROP INDEX IF EXISTS idx_saved_payment_methods_customer_id;
--   DROP TABLE IF EXISTS saved_payment_methods;
-- Notes: soft delete via deleted_at; one default per customer via partial unique index;
--   upi_id and razorpay_token_id stored AES-256-GCM ciphertext (Base64); upi_handle is masked display only.

CREATE TABLE saved_payment_methods (
    id                  UUID PRIMARY KEY,
    customer_id         UUID NOT NULL REFERENCES customers (id),
    type                VARCHAR(10) NOT NULL,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    nickname            VARCHAR(50) NULL,
    upi_id              VARCHAR(512) NULL,
    upi_handle          VARCHAR(100) NULL,
    razorpay_token_id   VARCHAR(512) NULL,
    card_last4          CHAR(4) NULL,
    card_network        VARCHAR(15) NULL,
    card_type           VARCHAR(10) NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ NULL,
    CONSTRAINT chk_saved_payment_methods_type CHECK (type IN ('UPI', 'CARD')),
    CONSTRAINT chk_saved_payment_methods_upi CHECK (
        type <> 'UPI'
        OR (upi_id IS NOT NULL AND upi_handle IS NOT NULL AND razorpay_token_id IS NULL
            AND card_last4 IS NULL AND card_network IS NULL AND card_type IS NULL)
    ),
    CONSTRAINT chk_saved_payment_methods_card CHECK (
        type <> 'CARD'
        OR (razorpay_token_id IS NOT NULL AND card_last4 IS NOT NULL
            AND card_network IS NOT NULL AND card_type IS NOT NULL
            AND upi_id IS NULL AND upi_handle IS NULL)
    )
);

CREATE INDEX idx_saved_payment_methods_customer_id
    ON saved_payment_methods (customer_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_saved_payment_methods_customer_type
    ON saved_payment_methods (customer_id, type)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX idx_saved_payment_methods_one_default
    ON saved_payment_methods (customer_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;
