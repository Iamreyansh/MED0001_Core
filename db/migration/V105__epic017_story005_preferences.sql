-- EPIC-017 / STORY-005: Notification preferences + audit
-- Rollback:
--   DROP INDEX IF EXISTS idx_pref_audit_entity_changed;
--   DROP TABLE IF EXISTS notification_preference_audit;
--   DROP TABLE IF EXISTS pharmacy_notification_preferences;
--   DROP TABLE IF EXISTS customer_notification_preferences;
-- Notes: lazy-create defaults on first GET; mandatory cats enforced in app
--        (customer: order_updates, account_critical;
--         pharmacy: order_alerts, kyc_updates, compliance_reminders).

CREATE TABLE customer_notification_preferences (
    id                      UUID PRIMARY KEY,
    customer_id             UUID NOT NULL,
    push_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    whatsapp_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    cat_order_updates       BOOLEAN NOT NULL DEFAULT TRUE,
    cat_account_critical    BOOLEAN NOT NULL DEFAULT TRUE,
    cat_promotions          BOOLEAN NOT NULL DEFAULT TRUE,
    cat_refill_reminders    BOOLEAN NOT NULL DEFAULT TRUE,
    cat_offers              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_notification_preferences_customer UNIQUE (customer_id)
);

CREATE TABLE pharmacy_notification_preferences (
    id                          UUID PRIMARY KEY,
    pharmacy_id                 UUID NOT NULL,
    push_enabled                BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    whatsapp_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    cat_order_alerts            BOOLEAN NOT NULL DEFAULT TRUE,
    cat_settlement_updates      BOOLEAN NOT NULL DEFAULT TRUE,
    cat_kyc_updates             BOOLEAN NOT NULL DEFAULT TRUE,
    cat_low_stock_alerts        BOOLEAN NOT NULL DEFAULT TRUE,
    cat_compliance_reminders    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_notification_preferences_pharmacy UNIQUE (pharmacy_id)
);

CREATE TABLE notification_preference_audit (
    id              UUID PRIMARY KEY,
    entity_type     VARCHAR(10) NOT NULL,
    entity_id       UUID NOT NULL,
    changed_by      UUID,
    change_source   VARCHAR(20) NOT NULL,
    old_values      JSONB NOT NULL,
    new_values      JSONB NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pref_audit_entity_type CHECK (entity_type IN ('CUSTOMER', 'PHARMACY')),
    CONSTRAINT chk_pref_audit_change_source CHECK (change_source IN (
        'USER', 'UNSUBSCRIBE_LINK', 'SPAM_REPORT', 'SYSTEM'
    ))
);

CREATE INDEX idx_pref_audit_entity_changed
    ON notification_preference_audit (entity_type, entity_id, changed_at DESC);
