-- EPIC-011 / STORY-007: COD reconciliation (rider side)
-- Rollback:
--   DELETE FROM platform_pricing_config WHERE key = 'cod_float_limit_default';
--   DROP TABLE IF EXISTS cod_collections;
--   DROP TABLE IF EXISTS cod_deposits;
-- Notes: Money as BIGINT paise. cod_float_limit_default = ₹2000 = 200000 paise.
--        Deposit requests stay PENDING until admin mark-deposited (BR-004).
--        Finance ledger / email body deferred to EPIC-012 (daily report via outbox stub).

INSERT INTO platform_pricing_config (key, value, description, updated_at)
VALUES (
    'cod_float_limit_default',
    '200000',
    'Default COD float limit per rider in paise (₹2000)',
    NOW()
)
ON CONFLICT (key) DO NOTHING;

CREATE TABLE cod_deposits (
    id                 UUID PRIMARY KEY,
    rider_id           UUID         NOT NULL REFERENCES riders (id),
    amount_paise       BIGINT       NOT NULL,
    deposit_mode       VARCHAR(16)  NOT NULL,
    reference_number   VARCHAR(100) NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    submitted_at       TIMESTAMPTZ  NOT NULL,
    confirmed_at       TIMESTAMPTZ,
    confirmed_by       UUID,
    deposited_at       TIMESTAMPTZ,
    notes              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT chk_cod_deposits_amount CHECK (amount_paise > 0),
    CONSTRAINT chk_cod_deposits_mode CHECK (deposit_mode IN ('BRANCH', 'UPI')),
    CONSTRAINT chk_cod_deposits_status CHECK (
        status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED')
    )
);

CREATE UNIQUE INDEX uq_cod_deposits_reference_active
    ON cod_deposits (reference_number)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_cod_deposits_rider
    ON cod_deposits (rider_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_cod_deposits_status
    ON cod_deposits (status)
    WHERE deleted_at IS NULL;

CREATE TABLE cod_collections (
    id                 UUID PRIMARY KEY,
    rider_id           UUID        NOT NULL REFERENCES riders (id),
    order_id           UUID        NOT NULL REFERENCES orders (id),
    cod_amount_paise   BIGINT      NOT NULL,
    collected_at       TIMESTAMPTZ NOT NULL,
    deposit_id         UUID REFERENCES cod_deposits (id),
    is_deposited       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cod_collections_amount CHECK (cod_amount_paise > 0),
    CONSTRAINT uq_cod_collections_order UNIQUE (order_id)
);

CREATE INDEX idx_cod_collections_rider
    ON cod_collections (rider_id);

CREATE INDEX idx_cod_collections_rider_undeposited
    ON cod_collections (rider_id, collected_at)
    WHERE is_deposited = FALSE;
