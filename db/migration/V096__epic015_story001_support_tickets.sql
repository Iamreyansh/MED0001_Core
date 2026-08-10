-- EPIC-015 / STORY-001: support tickets, messages, agent profiles, daily ticket_id seq
-- Rollback:
--   DROP INDEX IF EXISTS idx_support_tickets_sla_due_at;
--   DROP INDEX IF EXISTS idx_support_tickets_assigned_agent;
--   DROP INDEX IF EXISTS idx_support_tickets_priority;
--   DROP INDEX IF EXISTS idx_support_tickets_status;
--   DROP TABLE IF EXISTS support_ticket_messages;
--   DROP TABLE IF EXISTS support_tickets;
--   DROP TABLE IF EXISTS support_ticket_id_seq;
--   DROP TABLE IF EXISTS support_agent_profiles;
-- Notes: status includes AWAITING_CUSTOMER (SLA pause). SLA defaults until STORY-003:
--   LOW=L1/30m, MEDIUM=L2/2h, HIGH=L3/8h, URGENT=L4/24h. Agent cap default 20 (STORY-004).

CREATE TABLE support_agent_profiles (
    admin_user_id   UUID PRIMARY KEY REFERENCES admin_staff (id),
    specialties     TEXT[] NOT NULL DEFAULT '{}',
    is_online       BOOLEAN NOT NULL DEFAULT FALSE,
    max_load        INTEGER NOT NULL DEFAULT 20,
    display_name    VARCHAR(100) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE support_ticket_id_seq (
    day_key     DATE PRIMARY KEY,
    last_seq    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE support_tickets (
    id                          UUID PRIMARY KEY,
    ticket_id                   VARCHAR(22) NOT NULL,
    customer_id                 UUID NOT NULL,
    pharmacy_id                 UUID,
    order_id                    UUID,
    category                    VARCHAR(20) NOT NULL,
    subject                     VARCHAR(200) NOT NULL,
    status                      VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    priority                    VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    sla_level                   VARCHAR(5) NOT NULL,
    sla_due_at                  TIMESTAMPTZ NOT NULL,
    assigned_agent_id           UUID REFERENCES admin_staff (id),
    channel                     VARCHAR(20) NOT NULL,
    first_response_at           TIMESTAMPTZ,
    resolved_at                 TIMESTAMPTZ,
    resolution_summary          TEXT,
    csat_score                  INTEGER,
    csat_feedback               TEXT,
    csat_survey_scheduled_at    TIMESTAMPTZ,
    csat_survey_sent_at         TIMESTAMPTZ,
    created_by_admin_id         UUID REFERENCES admin_staff (id),
    deleted_at                  TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_support_tickets_ticket_id UNIQUE (ticket_id),
    CONSTRAINT chk_support_tickets_category CHECK (category IN (
        'ORDER', 'PAYMENT', 'PHARMACY', 'RIDER', 'ACCOUNT', 'PRODUCT', 'OTHER'
    )),
    CONSTRAINT chk_support_tickets_status CHECK (status IN (
        'OPEN', 'IN_PROGRESS', 'AWAITING_CUSTOMER', 'RESOLVED', 'CLOSED'
    )),
    CONSTRAINT chk_support_tickets_priority CHECK (priority IN (
        'LOW', 'MEDIUM', 'HIGH', 'URGENT'
    )),
    CONSTRAINT chk_support_tickets_sla_level CHECK (sla_level IN (
        'L1', 'L2', 'L3', 'L4'
    )),
    CONSTRAINT chk_support_tickets_channel CHECK (channel IN (
        'APP', 'EMAIL', 'PHONE', 'WHATSAPP'
    )),
    CONSTRAINT chk_support_tickets_csat CHECK (
        csat_score IS NULL OR (csat_score >= 1 AND csat_score <= 5)
    )
);

CREATE INDEX idx_support_tickets_status ON support_tickets (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_support_tickets_priority ON support_tickets (priority) WHERE deleted_at IS NULL;
CREATE INDEX idx_support_tickets_assigned_agent
    ON support_tickets (assigned_agent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_support_tickets_sla_due_at
    ON support_tickets (sla_due_at) WHERE deleted_at IS NULL AND first_response_at IS NULL;

CREATE TABLE support_ticket_messages (
    id                      UUID PRIMARY KEY,
    ticket_id               UUID NOT NULL REFERENCES support_tickets (id),
    sender_type             VARCHAR(20) NOT NULL,
    sender_id               UUID NOT NULL,
    sender_name             VARCHAR(100) NOT NULL,
    message                 TEXT NOT NULL,
    is_internal_note        BOOLEAN NOT NULL DEFAULT FALSE,
    canned_response_id      UUID,
    attachments             TEXT[] NOT NULL DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_support_ticket_messages_sender CHECK (sender_type IN (
        'CUSTOMER', 'AGENT', 'SYSTEM', 'PHARMACY'
    ))
);

CREATE INDEX idx_support_ticket_messages_ticket ON support_ticket_messages (ticket_id, created_at);
