-- EPIC-019 / STORY-003: workflow / journey builder
-- Rollback: DROP TABLE IF EXISTS workflow_executions;
--           DROP TABLE IF EXISTS automation_workflows;
-- Notes: steps JSONB (ACTION|WAIT|BRANCH); max 20 steps enforced in app;
--        wait_until UTC for WAIT scheduler; one RUNNING per workflow+entity.

CREATE TABLE automation_workflows (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    trigger_id          VARCHAR(60) NOT NULL REFERENCES trigger_registry (trigger_id),
    steps               JSONB NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(15) NOT NULL DEFAULT 'INACTIVE',
    version             INTEGER NOT NULL DEFAULT 1,
    is_seed_workflow    BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_workflows_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX idx_automation_workflows_name
    ON automation_workflows (LOWER(name));

CREATE INDEX idx_automation_workflows_status
    ON automation_workflows (status);

CREATE INDEX idx_automation_workflows_trigger
    ON automation_workflows (trigger_id);

CREATE TABLE workflow_executions (
    id                      UUID PRIMARY KEY,
    workflow_id             UUID NOT NULL REFERENCES automation_workflows (id),
    workflow_version        INTEGER NOT NULL,
    entity_type             VARCHAR(30) NOT NULL,
    entity_id               UUID NOT NULL,
    entity_name             VARCHAR(200),
    current_step_id         VARCHAR(20),
    status                  VARCHAR(15) NOT NULL,
    wait_until              TIMESTAMPTZ,
    context                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ,
    last_step_executed_at   TIMESTAMPTZ,
    step_history            JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT chk_workflow_executions_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED'))
);

CREATE INDEX idx_workflow_executions_workflow
    ON workflow_executions (workflow_id, started_at DESC);

CREATE INDEX idx_workflow_executions_status
    ON workflow_executions (workflow_id, status);

CREATE UNIQUE INDEX idx_workflow_executions_one_running
    ON workflow_executions (workflow_id, entity_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_workflow_executions_wait_due
    ON workflow_executions (wait_until)
    WHERE status = 'RUNNING' AND wait_until IS NOT NULL;
