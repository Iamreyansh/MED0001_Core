-- EPIC-019 / STORY-007: automation health & kill switch
-- Rollback: DROP TABLE IF EXISTS automation_deferred_executions;
--           DROP TABLE IF EXISTS automation_circuit_breakers;
--           DROP TABLE IF EXISTS automation_kill_switch_log;
--           DELETE FROM automation_health_config
--             WHERE config_key IN (
--               'dedup_window_seconds', 'approval_expiry_hours', 'circuit_reset_minutes');
--           ALTER TABLE automation_health_config DROP COLUMN IF EXISTS updated_by;
-- Notes: expands thin STORY-001 health_config keys; kill-switch audit is immutable
--        (separate from activity_log). Circuit breakers are per action_type.
--        deferred_executions queues approved actions while kill switch is PAUSED.

ALTER TABLE automation_health_config
    ALTER COLUMN config_value TYPE TEXT,
    ADD COLUMN IF NOT EXISTS updated_by UUID;

INSERT INTO automation_health_config (config_key, config_value) VALUES
    ('dedup_window_seconds', '300'),
    ('approval_expiry_hours', '4'),
    ('circuit_reset_minutes', '30')
ON CONFLICT (config_key) DO NOTHING;

CREATE TABLE automation_kill_switch_log (
    id          UUID PRIMARY KEY,
    action      VARCHAR(10) NOT NULL,
    changed_by  UUID NOT NULL,
    reason      TEXT NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_kill_switch_action
        CHECK (action IN ('PAUSE', 'RESUME'))
);

CREATE INDEX idx_automation_kill_switch_log_changed
    ON automation_kill_switch_log (changed_at DESC);

CREATE TABLE automation_circuit_breakers (
    action_type         VARCHAR(60) PRIMARY KEY,
    threshold_per_hour  INTEGER NOT NULL DEFAULT 50,
    circuit_status      VARCHAR(10) NOT NULL DEFAULT 'CLOSED',
    fires_last_hour     INTEGER NOT NULL DEFAULT 0,
    opened_at           TIMESTAMPTZ,
    reset_at            TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_circuit_status
        CHECK (circuit_status IN ('CLOSED', 'OPEN')),
    CONSTRAINT chk_automation_circuit_threshold
        CHECK (threshold_per_hour > 0)
);

INSERT INTO automation_circuit_breakers (action_type)
SELECT action_id FROM action_registry
ON CONFLICT (action_type) DO NOTHING;

CREATE TABLE automation_deferred_executions (
    id              UUID PRIMARY KEY,
    approval_id     UUID REFERENCES automation_approvals (id),
    action_type     VARCHAR(60) NOT NULL,
    action_params   JSONB NOT NULL DEFAULT '{}'::jsonb,
    execution_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_automation_deferred_created
    ON automation_deferred_executions (created_at);
