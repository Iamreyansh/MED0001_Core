-- EPIC-014 / STORY-004: CRM lead pipeline
-- Rollback:
--   DROP TABLE IF EXISTS crm_lead_activity;
--   DROP TABLE IF EXISTS crm_lead;
--   DROP TABLE IF EXISTS crm_lead_rr_cursor;
-- Notes: estimated_mrr stored as BIGINT paise; API exposes rupees.
--        Round-robin cursor advances across ACTIVE admin_super|admin_operations reps.

CREATE TABLE IF NOT EXISTS crm_lead (
    id UUID PRIMARY KEY,
    pharmacy_name VARCHAR(200) NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    email VARCHAR(255),
    source VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL DEFAULT 'NEW',
    win_probability INT NOT NULL DEFAULT 0,
    estimated_mrr_paise BIGINT,
    target_plan VARCHAR(32),
    assigned_rep_id UUID,
    notes TEXT,
    lost_reason VARCHAR(32),
    won_at TIMESTAMPTZ,
    lost_at TIMESTAMPTZ,
    sales_cycle_days INT,
    linked_account_id UUID REFERENCES crm_account (id),
    pharmacy_id UUID,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT crm_lead_source_chk CHECK (
        source IN ('ORGANIC', 'REFERRAL', 'AD', 'PARTNER', 'MARKETPLACE')
    ),
    CONSTRAINT crm_lead_stage_chk CHECK (
        stage IN ('NEW', 'CONTACTED', 'DEMO', 'TRIAL', 'WON', 'LOST')
    ),
    CONSTRAINT crm_lead_win_prob_chk CHECK (
        win_probability >= 0 AND win_probability <= 100
    ),
    CONSTRAINT crm_lead_lost_reason_chk CHECK (
        lost_reason IS NULL
        OR lost_reason IN ('PRICE', 'COMPETITOR', 'NOT_INTERESTED', 'TIMELINE', 'OTHER')
    )
);

CREATE INDEX IF NOT EXISTS idx_crm_lead_stage
    ON crm_lead (stage)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_crm_lead_rep
    ON crm_lead (assigned_rep_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_crm_lead_phone_open
    ON crm_lead (phone)
    WHERE deleted_at IS NULL AND stage NOT IN ('WON', 'LOST');

CREATE INDEX IF NOT EXISTS idx_crm_lead_pharmacy_open
    ON crm_lead (pharmacy_id)
    WHERE deleted_at IS NULL AND pharmacy_id IS NOT NULL AND stage NOT IN ('WON', 'LOST');

CREATE TABLE IF NOT EXISTS crm_lead_activity (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES crm_lead (id),
    event VARCHAR(50) NOT NULL,
    stage_from VARCHAR(32),
    stage_to VARCHAR(32),
    notes TEXT,
    actor_id UUID,
    actor_name VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_crm_lead_activity_lead
    ON crm_lead_activity (lead_id, created_at);

CREATE TABLE IF NOT EXISTS crm_lead_rr_cursor (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_rep_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO crm_lead_rr_cursor (id, last_rep_id, updated_at)
VALUES (1, NULL, NOW())
ON CONFLICT (id) DO NOTHING;
