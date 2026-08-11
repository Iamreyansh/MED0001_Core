-- EPIC-017 / STORY-004: Email templates, delivery logs, bounces, unsubscribes
-- Rollback:
--   DROP INDEX IF EXISTS idx_email_delivery_logs_sent_at;
--   DROP INDEX IF EXISTS idx_email_delivery_logs_status;
--   DROP INDEX IF EXISTS idx_email_delivery_logs_template;
--   DROP INDEX IF EXISTS idx_email_delivery_logs_to_email;
--   DROP INDEX IF EXISTS idx_email_delivery_logs_provider_msg;
--   DROP INDEX IF EXISTS idx_email_bounces_email_hard;
--   DROP INDEX IF EXISTS idx_email_unsubscribes_email_active;
--   DROP TABLE IF EXISTS email_delivery_logs;
--   DROP TABLE IF EXISTS email_bounces;
--   DROP TABLE IF EXISTS email_unsubscribes;
--   DROP TABLE IF EXISTS email_templates;
-- Notes: seed TRANSACTIONAL ORDER_CONFIRMATION/REFUND_PROCESSED/PASSWORD_RESET/ACCOUNT_SECURITY
--        + MARKETING WEEKLY_OFFERS for unsubscribe tests.

CREATE TABLE email_templates (
    template_id         VARCHAR(60) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    subject             TEXT NOT NULL,
    html_body           TEXT NOT NULL,
    text_body           TEXT NOT NULL,
    category            VARCHAR(15) NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    version             INTEGER NOT NULL DEFAULT 1,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_email_templates_category CHECK (category IN (
        'TRANSACTIONAL', 'LIFECYCLE', 'MARKETING'
    ))
);

CREATE TABLE email_delivery_logs (
    id                      UUID PRIMARY KEY,
    to_email                VARCHAR(320) NOT NULL,
    to_name                 VARCHAR(200),
    template_id             VARCHAR(60) NOT NULL REFERENCES email_templates (template_id),
    subject                 TEXT NOT NULL,
    provider                VARCHAR(10),
    fallback_used           BOOLEAN NOT NULL DEFAULT FALSE,
    provider_message_id     VARCHAR(200),
    status                  VARCHAR(15) NOT NULL,
    sent_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at            TIMESTAMPTZ,
    opened_at               TIMESTAMPTZ,
    clicked_at              TIMESTAMPTZ,
    bounce_type             VARCHAR(10),
    error_message           TEXT,
    CONSTRAINT chk_email_delivery_provider CHECK (
        provider IS NULL OR provider IN ('SENDGRID', 'SES')
    ),
    CONSTRAINT chk_email_delivery_status CHECK (status IN (
        'SENT', 'DELIVERED', 'OPENED', 'CLICKED', 'BOUNCED', 'SPAM'
    )),
    CONSTRAINT chk_email_delivery_bounce_type CHECK (
        bounce_type IS NULL OR bounce_type IN ('HARD', 'SOFT')
    )
);

CREATE INDEX idx_email_delivery_logs_to_email
    ON email_delivery_logs (to_email);

CREATE INDEX idx_email_delivery_logs_template
    ON email_delivery_logs (template_id);

CREATE INDEX idx_email_delivery_logs_status
    ON email_delivery_logs (status);

CREATE INDEX idx_email_delivery_logs_sent_at
    ON email_delivery_logs (sent_at DESC);

CREATE INDEX idx_email_delivery_logs_provider_msg
    ON email_delivery_logs (provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE TABLE email_bounces (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(320) NOT NULL,
    bounce_type         VARCHAR(10) NOT NULL,
    bounce_reason       TEXT,
    is_unsubscribed     BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_email_bounces_type CHECK (bounce_type IN ('HARD', 'SOFT'))
);

CREATE INDEX idx_email_bounces_email_hard
    ON email_bounces (email)
    WHERE bounce_type = 'HARD';

CREATE TABLE email_unsubscribes (
    id                      UUID PRIMARY KEY,
    email                   VARCHAR(320) NOT NULL,
    unsubscribe_source      VARCHAR(20) NOT NULL,
    unsubscribed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_email_unsubscribes_source CHECK (unsubscribe_source IN (
        'LINK_CLICK', 'SPAM_REPORT', 'MANUAL'
    ))
);

CREATE INDEX idx_email_unsubscribes_email_active
    ON email_unsubscribes (email)
    WHERE is_active = TRUE;

INSERT INTO email_templates (
    template_id, name, subject, html_body, text_body, category, is_active, version, created_at, updated_at
) VALUES (
    'ORDER_CONFIRMATION',
    'Order Confirmation',
    'Your Namma MedMate order #{{order_id}} is confirmed!',
    '<!DOCTYPE html><html><body><p>Hi {{customer_name}}, your order <strong>{{order_id}}</strong> for {{total_amount}} is confirmed.</p><p><a href="{{track_url}}">Track order</a></p><p>You are receiving this because it relates to your account activity.</p></body></html>',
    'Hi {{customer_name}}, your order {{order_id}} for {{total_amount}} is confirmed. Track: {{track_url}}. You are receiving this because it relates to your account activity.',
    'TRANSACTIONAL',
    TRUE,
    1,
    NOW(),
    NOW()
), (
    'REFUND_PROCESSED',
    'Refund Processed',
    'Refund of {{amount}} processed for order #{{order_id}}',
    '<!DOCTYPE html><html><body><p>Hi {{customer_name}}, your refund of <strong>{{amount}}</strong> for order {{order_id}} has been processed.</p><p>You are receiving this because it relates to your account activity.</p></body></html>',
    'Hi {{customer_name}}, your refund of {{amount}} for order {{order_id}} has been processed. You are receiving this because it relates to your account activity.',
    'TRANSACTIONAL',
    TRUE,
    1,
    NOW(),
    NOW()
), (
    'PASSWORD_RESET',
    'Password Reset',
    'Reset your Namma MedMate password',
    '<!DOCTYPE html><html><body><p>Hi {{customer_name}}, use this link to reset your password: {{reset_url}}</p><p>You are receiving this because it relates to your account activity.</p></body></html>',
    'Hi {{customer_name}}, reset your password: {{reset_url}}. You are receiving this because it relates to your account activity.',
    'TRANSACTIONAL',
    TRUE,
    1,
    NOW(),
    NOW()
), (
    'ACCOUNT_SECURITY',
    'Account Security Alert',
    'Security alert for your Namma MedMate account',
    '<!DOCTYPE html><html><body><p>Hi {{customer_name}}, {{alert_message}}</p><p>You are receiving this because it relates to your account activity.</p></body></html>',
    'Hi {{customer_name}}, {{alert_message}}. You are receiving this because it relates to your account activity.',
    'TRANSACTIONAL',
    TRUE,
    1,
    NOW(),
    NOW()
), (
    'WEEKLY_OFFERS',
    'Weekly Offers',
    'This week''s offers for you, {{customer_name}}',
    '<!DOCTYPE html><html><body><p>Hi {{customer_name}}, check out {{offer_title}}!</p>{{#if show_code}}<p>Use code {{promo_code}}</p>{{/if}}<p><a href="{{shop_url}}">Shop now</a></p></body></html>',
    'Hi {{customer_name}}, check out {{offer_title}}! Shop: {{shop_url}}',
    'MARKETING',
    TRUE,
    1,
    NOW(),
    NOW()
);
