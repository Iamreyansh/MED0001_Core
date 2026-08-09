-- EPIC-014: subscription charge idempotency
-- Rollback: DROP TABLE IF EXISTS crm_subscription_idempotency;
-- Notes: caches subscribe/upgrade JSON responses keyed by Idempotency-Key.

CREATE TABLE crm_subscription_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    operation VARCHAR(32) NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT crm_subscription_idempotency_op_chk CHECK (
        operation IN ('SUBSCRIBE', 'UPGRADE')
    )
);

CREATE INDEX idx_crm_subscription_idempotency_account
    ON crm_subscription_idempotency (account_id);
