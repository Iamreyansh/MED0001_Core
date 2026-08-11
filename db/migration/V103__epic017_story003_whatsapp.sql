-- EPIC-017 / STORY-003: WhatsApp templates, delivery logs, opt-outs, sessions
-- Rollback:
--   DROP INDEX IF EXISTS idx_whatsapp_delivery_logs_sent_at;
--   DROP INDEX IF EXISTS idx_whatsapp_delivery_logs_status;
--   DROP INDEX IF EXISTS idx_whatsapp_delivery_logs_template;
--   DROP INDEX IF EXISTS idx_whatsapp_delivery_logs_phone;
--   DROP INDEX IF EXISTS idx_whatsapp_delivery_logs_wa_msg;
--   DROP INDEX IF EXISTS idx_whatsapp_optouts_phone_active;
--   DROP TABLE IF EXISTS whatsapp_delivery_logs;
--   DROP TABLE IF EXISTS whatsapp_optouts;
--   DROP TABLE IF EXISTS whatsapp_sessions;
--   DROP TABLE IF EXISTS whatsapp_templates;
-- Notes: seed APPROVED UTILITY ORDER_CONFIRMED + MARKETING REFERRAL_REWARD; REJECTED sample for admin list.
--        AUTHENTICATION OTP optional V1 — not seeded. Costs: UTILITY 0.85 / MARKETING 2.00.

CREATE TABLE whatsapp_templates (
    id                  UUID PRIMARY KEY,
    template_name       VARCHAR(100) NOT NULL,
    category            VARCHAR(15) NOT NULL,
    language            VARCHAR(10) NOT NULL,
    status              VARCHAR(10) NOT NULL,
    body_text           TEXT NOT NULL,
    header_json         JSONB,
    footer_text         VARCHAR(200),
    buttons_json        JSONB,
    meta_template_id    VARCHAR(100),
    rejection_reason    TEXT,
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_at         TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    CONSTRAINT uq_whatsapp_templates_name UNIQUE (template_name),
    CONSTRAINT chk_whatsapp_templates_category CHECK (category IN (
        'UTILITY', 'MARKETING', 'AUTHENTICATION'
    )),
    CONSTRAINT chk_whatsapp_templates_status CHECK (status IN (
        'APPROVED', 'PENDING', 'REJECTED'
    ))
);

CREATE TABLE whatsapp_delivery_logs (
    id                  UUID PRIMARY KEY,
    to_phone            VARCHAR(15) NOT NULL,
    template_name       VARCHAR(100) NOT NULL REFERENCES whatsapp_templates (template_name),
    components_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    wa_message_id       VARCHAR(200),
    status              VARCHAR(10) NOT NULL,
    cost_rs             NUMERIC(6, 4),
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at        TIMESTAMPTZ,
    read_at             TIMESTAMPTZ,
    error_code          VARCHAR(20),
    error_message       TEXT,
    CONSTRAINT chk_whatsapp_delivery_status CHECK (status IN (
        'SENT', 'DELIVERED', 'READ', 'FAILED'
    ))
);

CREATE INDEX idx_whatsapp_delivery_logs_phone
    ON whatsapp_delivery_logs (to_phone);

CREATE INDEX idx_whatsapp_delivery_logs_template
    ON whatsapp_delivery_logs (template_name);

CREATE INDEX idx_whatsapp_delivery_logs_status
    ON whatsapp_delivery_logs (status);

CREATE INDEX idx_whatsapp_delivery_logs_sent_at
    ON whatsapp_delivery_logs (sent_at DESC);

CREATE INDEX idx_whatsapp_delivery_logs_wa_msg
    ON whatsapp_delivery_logs (wa_message_id)
    WHERE wa_message_id IS NOT NULL;

CREATE TABLE whatsapp_optouts (
    id                  UUID PRIMARY KEY,
    phone               VARCHAR(15) NOT NULL,
    optout_source       VARCHAR(10) NOT NULL,
    opted_out_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_whatsapp_optouts_source CHECK (optout_source IN (
        'WA_REPLY', 'IN_APP'
    ))
);

CREATE INDEX idx_whatsapp_optouts_phone_active
    ON whatsapp_optouts (phone)
    WHERE is_active = TRUE;

CREATE TABLE whatsapp_sessions (
    phone                       VARCHAR(15) PRIMARY KEY,
    last_customer_message_at    TIMESTAMPTZ NOT NULL
);

INSERT INTO whatsapp_templates (
    id, template_name, category, language, status, body_text,
    header_json, footer_text, buttons_json, meta_template_id,
    rejection_reason, submitted_at, approved_at, last_used_at
) VALUES (
    'b1000001-0000-4000-8000-000000000001',
    'ORDER_CONFIRMED',
    'UTILITY',
    'en',
    'APPROVED',
    'Hi {{1}}, your order from {{2}} for {{3}} has been confirmed. Estimated delivery: {{4}}.',
    '{"format":"TEXT","text":"Order Confirmed"}'::jsonb,
    'Namma MedMate',
    '[{"type":"URL","text":"Track Order","url":"https://app.nammamedmate.in/track/{{1}}"}]'::jsonb,
    'meta_tpl_order_confirmed',
    NULL,
    NOW() - INTERVAL '7 days',
    NOW() - INTERVAL '5 days',
    NULL
), (
    'b1000001-0000-4000-8000-000000000002',
    'REFERRAL_REWARD',
    'MARKETING',
    'en',
    'APPROVED',
    'Hi {{1}}, you earned Rs {{2}} referral reward. Tap to claim!',
    NULL,
    'Reply STOP to opt out',
    '[{"type":"URL","text":"Claim","url":"https://app.nammamedmate.in/rewards"}]'::jsonb,
    'meta_tpl_referral_reward',
    NULL,
    NOW() - INTERVAL '7 days',
    NOW() - INTERVAL '5 days',
    NULL
), (
    'b1000001-0000-4000-8000-000000000003',
    'PROMO_SUMMER_SALE',
    'MARKETING',
    'en',
    'REJECTED',
    'Get 20% off all vitamins this week! Use code SUMMER20.',
    NULL,
    NULL,
    '[]'::jsonb,
    NULL,
    'Content policy violation: discount percentage claims require proof',
    NOW() - INTERVAL '3 days',
    NULL,
    NULL
), (
    'b1000001-0000-4000-8000-000000000004',
    'KYC_PENDING_REVIEW',
    'UTILITY',
    'en',
    'PENDING',
    'Hi {{1}}, your KYC documents are under review.',
    NULL,
    NULL,
    '[]'::jsonb,
    NULL,
    NULL,
    NOW() - INTERVAL '1 day',
    NULL,
    NULL
);
