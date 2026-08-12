-- EPIC-019 / STORY-002: automation_rules CRUD
-- Rollback: DROP TABLE IF EXISTS automation_rules;
-- Notes: unique name among non-deleted; status ACTIVE|INACTIVE|SIMULATING;
--        seed rules cannot be deleted; max 200 ACTIVE enforced in app.

CREATE TABLE automation_rules (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    description             TEXT,
    trigger_id              VARCHAR(60) NOT NULL REFERENCES trigger_registry (trigger_id),
    trigger_params          JSONB NOT NULL DEFAULT '{}'::jsonb,
    conditions              JSONB NOT NULL DEFAULT '[]'::jsonb,
    actions                 JSONB NOT NULL DEFAULT '[]'::jsonb,
    guardrails              JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                  VARCHAR(15) NOT NULL DEFAULT 'INACTIVE',
    fire_count              INTEGER NOT NULL DEFAULT 0,
    last_fired_at           TIMESTAMPTZ,
    is_seed_rule            BOOLEAN NOT NULL DEFAULT FALSE,
    dedup_window_seconds    INTEGER NOT NULL DEFAULT 300,
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT chk_automation_rules_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SIMULATING'))
);

CREATE UNIQUE INDEX idx_automation_rules_name_active
    ON automation_rules (LOWER(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_automation_rules_status
    ON automation_rules (status)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_automation_rules_trigger
    ON automation_rules (trigger_id)
    WHERE deleted_at IS NULL;
