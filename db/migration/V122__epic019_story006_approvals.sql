-- EPIC-019 / STORY-006: automation approvals queue
-- Rollback: DROP TABLE IF EXISTS automation_approvals;
-- Notes: human-in-the-loop pause for value_cap / ALWAYS_REQUIRE_APPROVAL / require_approval.
--        Extra context columns (trigger_context, conditions_met) support GET :id detail.
--        No FK on rule_id / trigger_event_id so ephemeral evaluate rules can enqueue.

CREATE TABLE automation_approvals (
    id                      UUID PRIMARY KEY,
    rule_id                 UUID,
    rule_name               VARCHAR(200),
    trigger_event_id        UUID,
    trigger_event           VARCHAR(60),
    action_type             VARCHAR(60) NOT NULL,
    action_params           JSONB NOT NULL DEFAULT '{}'::jsonb,
    entity_type             VARCHAR(30) NOT NULL,
    entity_id               UUID,
    entity_name             VARCHAR(200),
    amount_paise            BIGINT,
    category                VARCHAR(20) NOT NULL,
    urgency                 VARCHAR(10) NOT NULL,
    why_requires_approval   TEXT,
    trigger_context         JSONB NOT NULL DEFAULT '{}'::jsonb,
    conditions_met          JSONB NOT NULL DEFAULT '[]'::jsonb,
    estimated_impact        TEXT,
    on_reject_action        VARCHAR(60),
    status                  VARCHAR(15) NOT NULL,
    approved_by             UUID,
    rejected_by             UUID,
    approval_notes          TEXT,
    rejection_reason        TEXT,
    activity_log_id         UUID,
    triggered_at            TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    resolved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_approvals_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT chk_automation_approvals_urgency
        CHECK (urgency IN ('URGENT', 'NORMAL')),
    CONSTRAINT chk_automation_approvals_category
        CHECK (category IN ('FINANCE', 'ADMIN', 'CRM'))
);

CREATE UNIQUE INDEX idx_automation_approvals_pending_dedup
    ON automation_approvals (rule_id, entity_id, action_type)
    WHERE status = 'PENDING';

CREATE INDEX idx_automation_approvals_status_triggered
    ON automation_approvals (status, triggered_at DESC);

CREATE INDEX idx_automation_approvals_pending_expiry
    ON automation_approvals (expires_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_automation_approvals_urgency
    ON automation_approvals (urgency, status);
