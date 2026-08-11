-- EPIC-017 / STORY-006: in-app notification inbox + cross-channel dispatch log view
-- Rollback:
--   DROP VIEW IF EXISTS notification_dispatch_log;
--   DROP INDEX IF EXISTS idx_in_app_notif_expires;
--   DROP INDEX IF EXISTS idx_in_app_notif_customer_unread;
--   DROP INDEX IF EXISTS idx_in_app_notif_customer_created;
--   DROP TABLE IF EXISTS customer_in_app_notifications;
-- Notes: default TTL 30d via expires_at; ORDER_UPDATE uses 90d at insert time.
--        Soft-deleted rows retained until TTL cleanup hard-deletes (expires_at + 30d).

CREATE TABLE customer_in_app_notifications (
    id              UUID PRIMARY KEY,
    customer_id     UUID NOT NULL,
    type            VARCHAR(20) NOT NULL,
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    action_url      TEXT,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_in_app_notif_type CHECK (type IN (
        'ORDER_UPDATE', 'PROMO', 'REFILL_REMINDER', 'SYSTEM'
    ))
);

CREATE INDEX idx_in_app_notif_customer_created
    ON customer_in_app_notifications (customer_id, created_at DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_in_app_notif_customer_unread
    ON customer_in_app_notifications (customer_id)
    WHERE is_deleted = FALSE AND is_read = FALSE;

CREATE INDEX idx_in_app_notif_expires
    ON customer_in_app_notifications (expires_at)
    WHERE is_deleted = FALSE;

CREATE OR REPLACE VIEW notification_dispatch_log AS
SELECT
    id                      AS dispatch_id,
    recipient_user_id       AS recipient_id,
    recipient_type::varchar(15) AS recipient_type,
    'PUSH'::varchar(10)     AS channel,
    NULL::varchar(20)       AS type,
    title,
    status::varchar(15)     AS status,
    sent_at,
    delivered_at
FROM push_notification_logs
UNION ALL
SELECT
    id,
    NULL::uuid,
    NULL::varchar(15),
    'SMS'::varchar(10),
    template_id::varchar(20),
    template_id,
    status::varchar(15),
    sent_at,
    delivered_at
FROM sms_delivery_logs
UNION ALL
SELECT
    id,
    NULL::uuid,
    NULL::varchar(15),
    'WHATSAPP'::varchar(10),
    template_name::varchar(20),
    template_name,
    status::varchar(15),
    sent_at,
    delivered_at
FROM whatsapp_delivery_logs
UNION ALL
SELECT
    id,
    NULL::uuid,
    NULL::varchar(15),
    'EMAIL'::varchar(10),
    template_id::varchar(20),
    subject,
    status::varchar(15),
    sent_at,
    delivered_at
FROM email_delivery_logs;
