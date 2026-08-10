-- EPIC-015 / STORY-003: SLA policies, escalation matrix, ticket dual due dates
-- Rollback:
--   ALTER TABLE support_tickets DROP COLUMN IF EXISTS sla_l4_notified_at;
--   ALTER TABLE support_tickets DROP COLUMN IF EXISTS sla_paused_at;
--   ALTER TABLE support_tickets DROP COLUMN IF EXISTS resolution_due_at;
--   ALTER TABLE support_tickets DROP COLUMN IF EXISTS first_response_due_at;
--   DROP TABLE IF EXISTS support_escalation_matrix;
--   DROP TABLE IF EXISTS support_sla_policies;
-- Notes: Policy changes apply to new tickets only. priority ANY = category wildcard.
--   resolution_sla_minutes stores hours×60 from story table.

ALTER TABLE support_tickets
    ADD COLUMN IF NOT EXISTS first_response_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolution_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sla_paused_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sla_l4_notified_at TIMESTAMPTZ;

UPDATE support_tickets
SET first_response_due_at = COALESCE(first_response_due_at, sla_due_at),
    resolution_due_at = COALESCE(resolution_due_at, created_at + INTERVAL '48 hours')
WHERE first_response_due_at IS NULL OR resolution_due_at IS NULL;

ALTER TABLE support_tickets
    ALTER COLUMN first_response_due_at SET NOT NULL,
    ALTER COLUMN resolution_due_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_support_tickets_resolution_due_at
    ON support_tickets (resolution_due_at)
    WHERE deleted_at IS NULL AND resolved_at IS NULL;

CREATE TABLE support_sla_policies (
    id                          UUID PRIMARY KEY,
    category                    VARCHAR(20) NOT NULL,
    priority                    VARCHAR(10) NOT NULL,
    first_response_sla_minutes  INTEGER NOT NULL,
    resolution_sla_minutes      INTEGER NOT NULL,
    sla_level                   VARCHAR(5) NOT NULL,
    updated_by                  UUID,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_support_sla_policies_cat_pri UNIQUE (category, priority),
    CONSTRAINT chk_support_sla_policies_level CHECK (sla_level IN ('L1', 'L2', 'L3', 'L4')),
    CONSTRAINT chk_support_sla_policies_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT', 'ANY')
    )
);

CREATE TABLE support_escalation_matrix (
    id                          UUID PRIMARY KEY,
    level                       VARCHAR(5) NOT NULL,
    criteria                    TEXT NOT NULL,
    assigned_team               VARCHAR(100) NOT NULL,
    notification_channels       TEXT[] NOT NULL,
    auto_escalate_after_minutes INTEGER NOT NULL,
    updated_by                  UUID,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_support_escalation_matrix_level UNIQUE (level),
    CONSTRAINT chk_support_escalation_matrix_level CHECK (level IN ('L1', 'L2', 'L3', 'L4'))
);

-- Seed SLA defaults (resolution hours × 60)
INSERT INTO support_sla_policies (
    id, category, priority, first_response_sla_minutes, resolution_sla_minutes, sla_level
) VALUES
    ('a1500003-0001-4000-8000-000000000001', 'ALL', 'LOW', 30, 1440, 'L1'),
    ('a1500003-0001-4000-8000-000000000002', 'ALL', 'MEDIUM', 120, 2880, 'L2'),
    ('a1500003-0001-4000-8000-000000000003', 'ALL', 'HIGH', 480, 4320, 'L3'),
    ('a1500003-0001-4000-8000-000000000004', 'ALL', 'URGENT', 1440, 5760, 'L4'),
    ('a1500003-0001-4000-8000-000000000005', 'ORDER', 'ANY', 30, 480, 'L1'),
    ('a1500003-0001-4000-8000-000000000006', 'PAYMENT', 'HIGH', 120, 1440, 'L2');

INSERT INTO support_escalation_matrix (
    id, level, criteria, assigned_team, notification_channels, auto_escalate_after_minutes
) VALUES
    ('a1500003-0002-4000-8000-000000000001', 'L1', 'Default assignment', 'Front-line Agents',
     ARRAY['IN_APP'], 30),
    ('a1500003-0002-4000-8000-000000000002', 'L2', 'L1 first-response breach', 'Senior Agents',
     ARRAY['IN_APP', 'WHATSAPP'], 120),
    ('a1500003-0002-4000-8000-000000000003', 'L3', 'L2 breach', 'Team Lead',
     ARRAY['IN_APP', 'WHATSAPP'], 480),
    ('a1500003-0002-4000-8000-000000000004', 'L4', 'L3 breach', 'Senior Ops Manager',
     ARRAY['IN_APP', 'WHATSAPP', 'CALL'], 1440);
