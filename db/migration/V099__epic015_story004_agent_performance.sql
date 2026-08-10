-- EPIC-015 / STORY-004: agent performance weekly snapshots
-- Rollback: DROP TABLE IF EXISTS support_agent_performance_snapshots;
-- Notes: CSAT / handle-time computed live from support_tickets; max_load default 20 already on profiles.
--   week_start is ISO Monday (Asia/Kolkata calendar week). UNIQUE(agent_id, week_start).

ALTER TABLE support_agent_profiles
    ALTER COLUMN max_load SET DEFAULT 20;

CREATE TABLE support_agent_performance_snapshots (
    id                   UUID PRIMARY KEY,
    agent_id             UUID NOT NULL REFERENCES admin_staff (id),
    week_start           DATE NOT NULL,
    tickets_handled      INTEGER NOT NULL DEFAULT 0,
    avg_handle_minutes   DECIMAL(6, 2),
    csat_score_avg       DECIMAL(3, 2),
    sla_breach_count     INTEGER NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_support_agent_perf_week UNIQUE (agent_id, week_start),
    CONSTRAINT chk_support_agent_perf_csat CHECK (
        csat_score_avg IS NULL OR (csat_score_avg >= 1.00 AND csat_score_avg <= 5.00)
    )
);

CREATE INDEX idx_support_agent_perf_agent
    ON support_agent_performance_snapshots (agent_id, week_start DESC);
