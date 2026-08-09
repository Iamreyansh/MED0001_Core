-- EPIC-014 / STORY-006: Feature adoption metering + per-account module overrides
-- Rollback:
--   DROP TABLE IF EXISTS saas_module_usage_monthly;
--   DROP TABLE IF EXISTS crm_account_module_override;
-- Notes: Monthly aggregates only (no raw event stream). Prior months stay in-table
--        (archive-by-retention); "reset" = new event_month bucket. Override beats plan matrix.

CREATE TABLE IF NOT EXISTS saas_module_usage_monthly (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    module_id VARCHAR(50) NOT NULL,
    event_month DATE NOT NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    last_active_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_saas_module_usage_monthly UNIQUE (account_id, module_id, event_month),
    CONSTRAINT chk_saas_module_usage_event_count CHECK (event_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_saas_module_usage_module_month
    ON saas_module_usage_monthly (module_id, event_month);

CREATE INDEX IF NOT EXISTS idx_saas_module_usage_account_month
    ON saas_module_usage_monthly (account_id, event_month);

CREATE TABLE IF NOT EXISTS crm_account_module_override (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    module_id VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL,
    reason TEXT NOT NULL,
    toggled_by UUID NOT NULL,
    toggled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_crm_account_module_override UNIQUE (account_id, module_id)
);

CREATE INDEX IF NOT EXISTS idx_crm_account_module_override_module
    ON crm_account_module_override (module_id);
