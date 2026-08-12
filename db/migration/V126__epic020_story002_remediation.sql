-- EPIC-020 / STORY-002: auto-remediation playbooks + log
-- Rollback:
--   DROP TABLE IF EXISTS monitoring_remediation_log;
--   DROP TABLE IF EXISTS remediation_playbooks;
--   ALTER TABLE monitoring_alerts DROP CONSTRAINT chk_monitoring_alerts_type;
--   ALTER TABLE monitoring_alerts ADD CONSTRAINT chk_monitoring_alerts_type
--     CHECK (type IN (
--       'GMV_DROP','DISPATCH_FAILURE','ZONE_DARK','PAYOUT_SPIKE',
--       'PAYMENT_FAILURE','SLA_BREACH_RATE','SLO_ERROR_BUDGET_EXHAUSTED'));
-- Notes: playbooks distinct from EPIC-019 rules; triggered_by/updated_by → admin_staff;
--   alert type CHECK widened for LOW_FILL_RATE, PAYMENT_JOB_FAILURE, API_ERROR_RATE_HIGH.

ALTER TABLE monitoring_alerts DROP CONSTRAINT chk_monitoring_alerts_type;
ALTER TABLE monitoring_alerts ADD CONSTRAINT chk_monitoring_alerts_type
    CHECK (type IN (
        'GMV_DROP',
        'DISPATCH_FAILURE',
        'ZONE_DARK',
        'PAYOUT_SPIKE',
        'PAYMENT_FAILURE',
        'SLA_BREACH_RATE',
        'SLO_ERROR_BUDGET_EXHAUSTED',
        'LOW_FILL_RATE',
        'PAYMENT_JOB_FAILURE',
        'API_ERROR_RATE_HIGH'
    ));

CREATE TABLE remediation_playbooks (
    id                          UUID PRIMARY KEY,
    alert_type                  VARCHAR(40)  NOT NULL,
    auto_remediation_action     VARCHAR(40)  NOT NULL,
    description                 TEXT         NOT NULL,
    threshold                   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    last_triggered_at           TIMESTAMPTZ,
    updated_by                  UUID REFERENCES admin_staff (id),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_remediation_playbooks_alert_type UNIQUE (alert_type),
    CONSTRAINT chk_remediation_playbooks_action CHECK (auto_remediation_action IN (
        'REQUEST_RIDERS',
        'THROTTLE_PHARMACY',
        'RETRY_PAYMENT_JOB',
        'CLEAR_CACHE',
        'PAGE_ON_CALL',
        'PAUSE_PROMOTION'
    ))
);

CREATE TABLE monitoring_remediation_log (
    id                      UUID PRIMARY KEY,
    alert_id                UUID REFERENCES monitoring_alerts (id),
    playbook_id             UUID REFERENCES remediation_playbooks (id),
    action_type             VARCHAR(40)  NOT NULL,
    trigger_type            VARCHAR(10)  NOT NULL,
    target_entity_type      VARCHAR(20)  NOT NULL,
    target_entity_id        UUID         NOT NULL,
    action_details          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status                  VARCHAR(10)  NOT NULL,
    triggered_by            UUID REFERENCES admin_staff (id),
    triggered_at            TIMESTAMPTZ  NOT NULL,
    completed_at            TIMESTAMPTZ,
    error_message           TEXT,
    CONSTRAINT chk_remediation_log_action CHECK (action_type IN (
        'REQUEST_RIDERS',
        'THROTTLE_PHARMACY',
        'RETRY_PAYMENT_JOB',
        'CLEAR_CACHE',
        'PAGE_ON_CALL',
        'PAUSE_PROMOTION'
    )),
    CONSTRAINT chk_remediation_log_trigger CHECK (trigger_type IN ('AUTO', 'MANUAL')),
    CONSTRAINT chk_remediation_log_status CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_monitoring_remediation_log_rate
    ON monitoring_remediation_log (action_type, target_entity_id, triggered_at DESC);

INSERT INTO remediation_playbooks (
    id, alert_type, auto_remediation_action, description, threshold, is_enabled, updated_at)
VALUES
    (
        '02000002-0001-4000-8000-000000000001',
        'ZONE_DARK',
        'REQUEST_RIDERS',
        'Send push notifications to offline riders in the dark zone to come online.',
        '{"dark_duration_minutes":30,"max_notifications_per_rider":3,"notification_cooldown_hours":2}'::jsonb,
        TRUE,
        NOW()
    ),
    (
        '02000002-0001-4000-8000-000000000002',
        'LOW_FILL_RATE',
        'THROTTLE_PHARMACY',
        'Reduce pharmacy max concurrent order cap by 30% when fill_rate < threshold for N consecutive days.',
        '{"fill_rate_pct":70,"consecutive_days":3,"throttle_pct":30,"recovery_fill_rate_pct":80,"recovery_consecutive_days":2}'::jsonb,
        TRUE,
        NOW()
    ),
    (
        '02000002-0001-4000-8000-000000000003',
        'PAYMENT_JOB_FAILURE',
        'RETRY_PAYMENT_JOB',
        'Retry failed payment processing job after delay.',
        '{"retry_delay_minutes":5,"max_retries":3}'::jsonb,
        TRUE,
        NOW()
    ),
    (
        '02000002-0001-4000-8000-000000000004',
        'API_ERROR_RATE_HIGH',
        'PAGE_ON_CALL',
        'Page the on-call engineer when API error rate > 5% on any endpoint.',
        '{"error_rate_pct":5,"window_minutes":5}'::jsonb,
        TRUE,
        NOW()
    );
