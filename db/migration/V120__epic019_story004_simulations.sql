-- EPIC-019 / STORY-004: rule simulation
-- Rollback: DROP TABLE IF EXISTS automation_simulations;
--           ALTER TABLE automation_rules DROP COLUMN IF EXISTS simulating_started_at;
-- Notes: results retained 7d via expires_at; SIMULATING auto-revert uses simulating_started_at
--        (updated_at moves on fire_count so cannot drive the 24h cap).

ALTER TABLE automation_rules
    ADD COLUMN IF NOT EXISTS simulating_started_at TIMESTAMPTZ;

CREATE TABLE automation_simulations (
    id                          UUID PRIMARY KEY,
    rule_id                     UUID NOT NULL REFERENCES automation_rules (id),
    sample_size                 INTEGER NOT NULL,
    events_scanned              INTEGER NOT NULL DEFAULT 0,
    entities_matched            INTEGER NOT NULL DEFAULT 0,
    conditions_failed_count     INTEGER NOT NULL DEFAULT 0,
    false_positive_risk         VARCHAR(10),
    risk_details                TEXT,
    estimated_impact_summary    TEXT,
    results_json                JSONB NOT NULL DEFAULT '[]'::jsonb,
    status                      VARCHAR(15) NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL,
    completed_at                TIMESTAMPTZ,
    triggered_by                UUID,
    expires_at                  TIMESTAMPTZ,
    CONSTRAINT chk_automation_simulations_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_automation_simulations_risk
        CHECK (false_positive_risk IS NULL OR false_positive_risk IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE INDEX idx_automation_simulations_rule
    ON automation_simulations (rule_id, started_at DESC);

CREATE INDEX idx_automation_simulations_running
    ON automation_simulations (status)
    WHERE status = 'RUNNING';

CREATE INDEX idx_automation_simulations_expires
    ON automation_simulations (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX idx_automation_rules_simulating
    ON automation_rules (simulating_started_at)
    WHERE status = 'SIMULATING' AND deleted_at IS NULL;
