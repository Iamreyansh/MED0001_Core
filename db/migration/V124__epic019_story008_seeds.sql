-- EPIC-019 / STORY-008: seed automations catalog
-- Rollback: DROP TABLE IF EXISTS automation_seed_rule_catalog;
-- Notes: lookup only; actual rules/workflows inserted by initialize (INACTIVE).

CREATE TABLE automation_seed_rule_catalog (
    seed_rule_key     VARCHAR(60) PRIMARY KEY,
    rule_id           UUID REFERENCES automation_rules (id),
    workflow_id       UUID REFERENCES automation_workflows (id),
    display_order     SMALLINT NOT NULL,
    expected_impact   TEXT,
    edge_cases        TEXT,
    initialized_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_seed_catalog_target
        CHECK (rule_id IS NOT NULL OR workflow_id IS NOT NULL)
);

CREATE INDEX idx_seed_catalog_rule ON automation_seed_rule_catalog (rule_id)
    WHERE rule_id IS NOT NULL;
CREATE INDEX idx_seed_catalog_workflow ON automation_seed_rule_catalog (workflow_id)
    WHERE workflow_id IS NOT NULL;
CREATE INDEX idx_seed_catalog_order ON automation_seed_rule_catalog (display_order);
