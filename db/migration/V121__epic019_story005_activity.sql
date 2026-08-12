-- EPIC-019 / STORY-005: automation activity log & audit
-- Rollback: DROP TABLE IF EXISTS automation_activity_log;
-- Notes: append-only; rollbacks insert a new row (references_action_id).
--        Live API reads this table only; 2y Glacier archive is out of V1.

CREATE TABLE automation_activity_log (
    id                      UUID PRIMARY KEY,
    rule_id                 UUID,
    workflow_execution_id   UUID,
    trigger_event_id        UUID,
    entity_type             VARCHAR(30) NOT NULL,
    entity_id               UUID,
    entity_name             VARCHAR(200),
    action_type             VARCHAR(60) NOT NULL,
    action_params           JSONB NOT NULL DEFAULT '{}'::jsonb,
    conditions_evaluated    JSONB NOT NULL DEFAULT '[]'::jsonb,
    before_state            JSONB,
    after_state             JSONB,
    status                  VARCHAR(30) NOT NULL,
    actor                   VARCHAR(15) NOT NULL,
    override_by             UUID,
    triggered_at            TIMESTAMPTZ NOT NULL,
    executed_at             TIMESTAMPTZ,
    execution_ms            INTEGER,
    references_action_id    UUID REFERENCES automation_activity_log (id),
    error_message           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_activity_status
        CHECK (status IN (
            'EXECUTED', 'SIMULATED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
            'ROLLED_BACK', 'RATE_LIMITED', 'DUPLICATE_SKIPPED', 'EXCEPTION',
            'KILL_SWITCH_PAUSED'
        )),
    CONSTRAINT chk_automation_activity_actor
        CHECK (actor IN ('AUTOMATION', 'HUMAN'))
);

CREATE INDEX idx_automation_activity_rule_triggered
    ON automation_activity_log (rule_id, triggered_at DESC);

CREATE INDEX idx_automation_activity_entity_triggered
    ON automation_activity_log (entity_type, entity_id, triggered_at DESC);

CREATE INDEX idx_automation_activity_status_triggered
    ON automation_activity_log (status, triggered_at DESC);

CREATE INDEX idx_automation_activity_references
    ON automation_activity_log (references_action_id)
    WHERE references_action_id IS NOT NULL;
