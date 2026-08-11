-- EPIC-017 / STORY-001: push notifications — device tokens, broadcasts, delivery logs
-- Rollback:
--   DROP INDEX IF EXISTS idx_push_logs_broadcast;
--   DROP INDEX IF EXISTS idx_push_logs_sent_at;
--   DROP INDEX IF EXISTS idx_push_logs_status;
--   DROP INDEX IF EXISTS idx_push_logs_recipient;
--   DROP INDEX IF EXISTS idx_push_broadcasts_queued;
--   DROP INDEX IF EXISTS idx_device_tokens_active_user;
--   DROP TABLE IF EXISTS push_notification_logs;
--   DROP TABLE IF EXISTS push_broadcasts;
--   DROP TABLE IF EXISTS device_tokens;
-- Notes: audience resolved at broadcast execution; FCM NOT_REGISTERED → is_active=false.

CREATE TABLE device_tokens (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL,
    user_type           VARCHAR(15) NOT NULL,
    token               TEXT NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    device_id           VARCHAR(200) NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_refreshed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_device_tokens_user_type CHECK (user_type IN (
        'CUSTOMER', 'PHARMACY_STAFF', 'RIDER'
    )),
    CONSTRAINT chk_device_tokens_platform CHECK (platform IN ('IOS', 'ANDROID')),
    CONSTRAINT uq_device_tokens_user_device UNIQUE (user_type, user_id, device_id)
);

CREATE INDEX idx_device_tokens_active_user
    ON device_tokens (user_type, user_id)
    WHERE is_active = TRUE;

CREATE TABLE push_broadcasts (
    id                      UUID PRIMARY KEY,
    audience                VARCHAR(30) NOT NULL,
    title                   TEXT,
    body                    TEXT,
    data                    JSONB NOT NULL DEFAULT '{}'::jsonb,
    schedule_at             TIMESTAMPTZ,
    status                  VARCHAR(15) NOT NULL DEFAULT 'QUEUED',
    estimated_recipients    INTEGER NOT NULL DEFAULT 0,
    created_by              UUID NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    executed_at             TIMESTAMPTZ,
    CONSTRAINT chk_push_broadcasts_audience CHECK (audience IN (
        'ALL_CUSTOMERS', 'ALL_PHARMACIES', 'ALL_RIDERS'
    )),
    CONSTRAINT chk_push_broadcasts_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'
    ))
);

CREATE INDEX idx_push_broadcasts_queued
    ON push_broadcasts (schedule_at, created_at)
    WHERE status = 'QUEUED';

CREATE TABLE push_notification_logs (
    id                  UUID PRIMARY KEY,
    broadcast_id        UUID REFERENCES push_broadcasts (id),
    recipient_user_id   UUID NOT NULL,
    recipient_type      VARCHAR(15) NOT NULL,
    device_token_id     UUID REFERENCES device_tokens (id),
    title               TEXT,
    body                TEXT,
    priority            VARCHAR(6) NOT NULL,
    fcm_message_id      VARCHAR(200),
    status              VARCHAR(15) NOT NULL,
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at        TIMESTAMPTZ,
    opened_at           TIMESTAMPTZ,
    error_message       TEXT,
    CONSTRAINT chk_push_logs_recipient_type CHECK (recipient_type IN (
        'CUSTOMER', 'PHARMACY_STAFF', 'RIDER'
    )),
    CONSTRAINT chk_push_logs_priority CHECK (priority IN ('HIGH', 'NORMAL')),
    CONSTRAINT chk_push_logs_status CHECK (status IN ('SENT', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_push_logs_recipient
    ON push_notification_logs (recipient_type, recipient_user_id);

CREATE INDEX idx_push_logs_status
    ON push_notification_logs (status);

CREATE INDEX idx_push_logs_sent_at
    ON push_notification_logs (sent_at DESC);

CREATE INDEX idx_push_logs_broadcast
    ON push_notification_logs (broadcast_id)
    WHERE broadcast_id IS NOT NULL;
