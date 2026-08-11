-- EPIC-017 / STORY-002: SMS templates + delivery logs (cost on logs)
-- Rollback:
--   DROP INDEX IF EXISTS idx_sms_delivery_logs_sent_at;
--   DROP INDEX IF EXISTS idx_sms_delivery_logs_status;
--   DROP INDEX IF EXISTS idx_sms_delivery_logs_template;
--   DROP INDEX IF EXISTS idx_sms_delivery_logs_phone;
--   DROP INDEX IF EXISTS idx_sms_delivery_logs_provider_msg;
--   DROP TABLE IF EXISTS sms_delivery_logs;
--   DROP TABLE IF EXISTS sms_templates;
-- Notes: cost_rs on delivery logs (no sms_cost_log); seed OTP_VERIFICATION for tests.

CREATE TABLE sms_templates (
    template_id         VARCHAR(60) PRIMARY KEY,
    content             TEXT NOT NULL,
    category            VARCHAR(15) NOT NULL,
    dlt_template_id     VARCHAR(20),
    sender_id           VARCHAR(6) NOT NULL DEFAULT 'NMMATE',
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sms_templates_category CHECK (category IN (
        'OTP', 'TRANSACTIONAL', 'PROMOTIONAL'
    ))
);

CREATE TABLE sms_delivery_logs (
    id                      UUID PRIMARY KEY,
    to_phone                VARCHAR(15) NOT NULL,
    template_id             VARCHAR(60) NOT NULL REFERENCES sms_templates (template_id),
    variables               JSONB NOT NULL DEFAULT '{}'::jsonb,
    provider                VARCHAR(10),
    provider_message_id     VARCHAR(100),
    fallback_used           BOOLEAN NOT NULL DEFAULT FALSE,
    status                  VARCHAR(15) NOT NULL,
    cost_rs                 NUMERIC(6, 4),
    sent_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at            TIMESTAMPTZ,
    error_message           TEXT,
    CONSTRAINT chk_sms_delivery_provider CHECK (
        provider IS NULL OR provider IN ('MSG91', 'TWILIO')
    ),
    CONSTRAINT chk_sms_delivery_status CHECK (status IN (
        'SENT', 'DELIVERED', 'FAILED', 'EXPIRED', 'SKIPPED_DND'
    ))
);

CREATE INDEX idx_sms_delivery_logs_phone
    ON sms_delivery_logs (to_phone);

CREATE INDEX idx_sms_delivery_logs_template
    ON sms_delivery_logs (template_id);

CREATE INDEX idx_sms_delivery_logs_status
    ON sms_delivery_logs (status);

CREATE INDEX idx_sms_delivery_logs_sent_at
    ON sms_delivery_logs (sent_at DESC);

CREATE INDEX idx_sms_delivery_logs_provider_msg
    ON sms_delivery_logs (provider_message_id)
    WHERE provider_message_id IS NOT NULL;

INSERT INTO sms_templates (
    template_id, content, category, dlt_template_id, sender_id, is_active, created_at
) VALUES (
    'OTP_VERIFICATION',
    'Your Namma MedMate OTP is {{1}}. Valid for {{2}} minutes. Do not share with anyone. - NMMATE',
    'OTP',
    '1007164875432101',
    'NMMATE',
    TRUE,
    NOW()
);
