-- Production integration: consumer inbox, provider ops, webhook inbox, outbox poison
-- Rollback:
--   DROP TABLE IF EXISTS webhook_inbox;
--   DROP TABLE IF EXISTS provider_operation;
--   DROP TABLE IF EXISTS consumer_inbox;
--   ALTER TABLE outbox_message DROP COLUMN IF EXISTS poisoned;
-- Notes: durable dedup + money-movement reconciliation. UUID ids; TIMESTAMPTZ.

ALTER TABLE outbox_message
    ADD COLUMN IF NOT EXISTS poisoned BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_outbox_poisoned
    ON outbox_message (created_at)
    WHERE published = FALSE AND poisoned = TRUE;

CREATE TABLE IF NOT EXISTS consumer_inbox (
    consumer_name VARCHAR(80)  NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE IF NOT EXISTS provider_operation (
    id               UUID         PRIMARY KEY,
    operation_type   VARCHAR(32)  NOT NULL,
    idempotency_key  VARCHAR(160) NOT NULL,
    provider         VARCHAR(32)  NOT NULL,
    provider_ref     VARCHAR(128),
    status           VARCHAR(24)  NOT NULL,
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provider_operation_type_key UNIQUE (operation_type, idempotency_key),
    CONSTRAINT chk_provider_operation_status CHECK (
        status IN ('PENDING', 'SENT', 'SUCCEEDED', 'FAILED', 'AMBIGUOUS')
    )
);

CREATE TABLE IF NOT EXISTS webhook_inbox (
    id                 UUID         PRIMARY KEY,
    provider           VARCHAR(32)  NOT NULL,
    provider_event_id  VARCHAR(128) NOT NULL,
    payload_json       TEXT         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at       TIMESTAMPTZ,
    CONSTRAINT uq_webhook_inbox_provider_event UNIQUE (provider, provider_event_id),
    CONSTRAINT chk_webhook_inbox_status CHECK (
        status IN ('RECEIVED', 'PROCESSED', 'FAILED')
    )
);
