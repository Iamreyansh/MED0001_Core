-- EPIC-002 / STORY-003: Namma Money wallets + ledger
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_customers_create_wallet ON customers;
--   DROP FUNCTION IF EXISTS create_wallet_for_customer();
--   DROP INDEX IF EXISTS idx_wallet_transactions_expired_credit;
--   DROP INDEX IF EXISTS idx_wallet_transactions_idempotency_key;
--   DROP INDEX IF EXISTS idx_wallet_transactions_open_credits;
--   DROP INDEX IF EXISTS idx_wallet_transactions_reference_id;
--   DROP INDEX IF EXISTS idx_wallet_transactions_wallet_created;
--   DROP TABLE IF EXISTS wallet_transactions;
--   DROP TABLE IF EXISTS wallets;
-- Notes: amounts in paise (BIGINT). remaining_paise tracks unspent CREDIT for FIFO debit/expiry.
--   Trigger ensures one wallet per customer on insert; backfill covers existing rows.
--   customers.wallet_balance_paise stays denormalised and is synced by the app on mutations.
--   idempotency_key dedupes admin credits; EXPIRED.reference_id unique per source credit.

CREATE TABLE wallets (
    id                       UUID PRIMARY KEY,
    customer_id              UUID NOT NULL UNIQUE REFERENCES customers (id),
    balance_paise            BIGINT NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
    lifetime_credited_paise  BIGINT NOT NULL DEFAULT 0 CHECK (lifetime_credited_paise >= 0),
    lifetime_debited_paise   BIGINT NOT NULL DEFAULT 0 CHECK (lifetime_debited_paise >= 0),
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL
);

CREATE TABLE wallet_transactions (
    id                   UUID PRIMARY KEY,
    wallet_id            UUID NOT NULL REFERENCES wallets (id),
    type                 VARCHAR(10) NOT NULL
        CHECK (type IN ('CREDIT', 'DEBIT', 'EXPIRED')),
    amount_paise         BIGINT NOT NULL CHECK (amount_paise > 0),
    balance_after_paise  BIGINT NOT NULL,
    reason               VARCHAR(30) NOT NULL
        CHECK (reason IN ('REFUND', 'GOODWILL', 'PROMOTIONAL', 'ORDER_PAYMENT', 'EXPIRY')),
    description          VARCHAR(500),
    reference_id         VARCHAR(255),
    idempotency_key      VARCHAR(255),
    credited_by          UUID NULL REFERENCES admin_staff (id),
    expires_at           TIMESTAMPTZ NULL,
    remaining_paise      BIGINT NULL CHECK (remaining_paise IS NULL OR remaining_paise >= 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wallet_transactions_wallet_created
    ON wallet_transactions (wallet_id, created_at DESC);

CREATE INDEX idx_wallet_transactions_reference_id
    ON wallet_transactions (reference_id)
    WHERE reference_id IS NOT NULL;

CREATE UNIQUE INDEX idx_wallet_transactions_idempotency_key
    ON wallet_transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- One EXPIRED ledger row per source CREDIT (reference_id = credit tx id).
CREATE UNIQUE INDEX idx_wallet_transactions_expired_credit
    ON wallet_transactions (reference_id)
    WHERE type = 'EXPIRED' AND reference_id IS NOT NULL;

CREATE INDEX idx_wallet_transactions_open_credits
    ON wallet_transactions (expires_at)
    WHERE type = 'CREDIT' AND remaining_paise > 0;

-- Auto-create empty wallet when a customer row is inserted.
CREATE OR REPLACE FUNCTION create_wallet_for_customer()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO wallets (
        id, customer_id, balance_paise, lifetime_credited_paise, lifetime_debited_paise,
        version, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), NEW.id, COALESCE(NEW.wallet_balance_paise, 0), 0, 0,
        0, NOW(), NOW()
    )
    ON CONFLICT (customer_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_customers_create_wallet
    AFTER INSERT ON customers
    FOR EACH ROW
    EXECUTE FUNCTION create_wallet_for_customer();

-- Backfill wallets for customers created before this migration.
INSERT INTO wallets (
    id, customer_id, balance_paise, lifetime_credited_paise, lifetime_debited_paise,
    version, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    c.id,
    c.wallet_balance_paise,
    0,
    0,
    0,
    COALESCE(c.created_at, NOW()),
    NOW()
FROM customers c
WHERE NOT EXISTS (
    SELECT 1 FROM wallets w WHERE w.customer_id = c.id
);
