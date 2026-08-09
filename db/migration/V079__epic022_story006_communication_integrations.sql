-- EPIC-022 / STORY-006: Communication Integrations (admin control plane)
-- Rollback:
--   DROP TABLE IF EXISTS communication_config_audit;
--   DROP TABLE IF EXISTS communication_cost_daily;
--   DROP TABLE IF EXISTS communication_channel_configs;
-- Notes: credentials live in Secrets Manager (secrets_manager_key); delivery remains EPIC-017.

CREATE TABLE communication_channel_configs (
    channel VARCHAR(10) PRIMARY KEY,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    provider VARCHAR(20) NOT NULL,
    fallback_provider VARCHAR(20),
    secrets_manager_key VARCHAR(200) NOT NULL,
    daily_send_limit INTEGER NOT NULL DEFAULT 50000,
    daily_sent_count INTEGER NOT NULL DEFAULT 0,
    current_status VARCHAR(10) NOT NULL DEFAULT 'HEALTHY',
    last_health_check_at TIMESTAMPTZ,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT communication_channel_configs_channel_chk CHECK (
        channel IN ('PUSH', 'SMS', 'WHATSAPP', 'EMAIL')
    ),
    CONSTRAINT communication_channel_configs_provider_chk CHECK (
        provider IN (
            'FIREBASE_FCM',
            'MSG91',
            'TWILIO',
            'META_CLOUD_API',
            'SENDGRID',
            'AWS_SES'
        )
    ),
    CONSTRAINT communication_channel_configs_fallback_chk CHECK (
        fallback_provider IS NULL
        OR fallback_provider IN (
            'FIREBASE_FCM',
            'MSG91',
            'TWILIO',
            'META_CLOUD_API',
            'SENDGRID',
            'AWS_SES'
        )
    ),
    CONSTRAINT communication_channel_configs_status_chk CHECK (
        current_status IN ('HEALTHY', 'DEGRADED', 'DOWN')
    ),
    CONSTRAINT communication_channel_configs_limit_chk CHECK (daily_send_limit >= 0),
    CONSTRAINT communication_channel_configs_sent_chk CHECK (daily_sent_count >= 0)
);

CREATE TABLE communication_cost_daily (
    id UUID PRIMARY KEY,
    date DATE NOT NULL,
    channel VARCHAR(10) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    sent_count INTEGER NOT NULL DEFAULT 0,
    delivered_count INTEGER NOT NULL DEFAULT 0,
    fallback_sent_count INTEGER NOT NULL DEFAULT 0,
    cost_rs DECIMAL(10, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT communication_cost_daily_channel_chk CHECK (
        channel IN ('PUSH', 'SMS', 'WHATSAPP', 'EMAIL')
    ),
    CONSTRAINT communication_cost_daily_uq UNIQUE (date, channel, provider)
);

CREATE INDEX idx_communication_cost_daily_channel_date
    ON communication_cost_daily (channel, date DESC);

CREATE TABLE communication_config_audit (
    id UUID PRIMARY KEY,
    channel VARCHAR(10) NOT NULL,
    changed_by UUID NOT NULL,
    changed_fields JSONB NOT NULL DEFAULT '{}'::jsonb,
    connectivity_test_result VARCHAR(10) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT communication_config_audit_channel_chk CHECK (
        channel IN ('PUSH', 'SMS', 'WHATSAPP', 'EMAIL')
    ),
    CONSTRAINT communication_config_audit_result_chk CHECK (
        connectivity_test_result IN ('PASSED', 'FAILED', 'SKIPPED')
    )
);

CREATE INDEX idx_communication_config_audit_channel_changed
    ON communication_config_audit (channel, changed_at DESC);

INSERT INTO communication_channel_configs (
    channel,
    is_enabled,
    provider,
    fallback_provider,
    secrets_manager_key,
    daily_send_limit,
    daily_sent_count,
    current_status,
    last_health_check_at,
    updated_by,
    updated_at
) VALUES
    (
        'PUSH',
        TRUE,
        'FIREBASE_FCM',
        NULL,
        'medmate/comms/push',
        100000,
        0,
        'HEALTHY',
        NOW(),
        NULL,
        NOW()
    ),
    (
        'SMS',
        TRUE,
        'MSG91',
        'TWILIO',
        'medmate/comms/sms',
        50000,
        0,
        'HEALTHY',
        NOW(),
        NULL,
        NOW()
    ),
    (
        'WHATSAPP',
        TRUE,
        'META_CLOUD_API',
        NULL,
        'medmate/comms/whatsapp',
        20000,
        0,
        'HEALTHY',
        NOW(),
        NULL,
        NOW()
    ),
    (
        'EMAIL',
        TRUE,
        'SENDGRID',
        'AWS_SES',
        'medmate/comms/email',
        100000,
        0,
        'HEALTHY',
        NOW(),
        NULL,
        NOW()
    );
