-- EPIC-022 / STORY-005: Accounting Integration (Tally XML + Zoho Books)
-- Rollback:
--   DROP TABLE IF EXISTS accounting_sync_jobs;
--   DROP TABLE IF EXISTS accounting_integrations;
-- Notes: Zoho OAuth tokens stored encrypted at app layer; pharmacy_id FK optional soft-coupling.

CREATE TABLE accounting_integrations (
    id UUID PRIMARY KEY,
    pharmacy_id UUID NOT NULL REFERENCES pharmacies (id),
    accounting_system VARCHAR(15) NOT NULL,
    zoho_organization_id VARCHAR(20),
    zoho_organization_name VARCHAR(200),
    zoho_access_token TEXT,
    zoho_refresh_token TEXT,
    zoho_token_expires_at TIMESTAMPTZ,
    api_key_status VARCHAR(15) NOT NULL DEFAULT 'DISCONNECTED',
    auto_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sync_frequency VARCHAR(10),
    next_sync_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    last_sync_status VARCHAR(15),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT accounting_integrations_system_chk CHECK (
        accounting_system IN ('TALLY', 'ZOHO_BOOKS')
    ),
    CONSTRAINT accounting_integrations_api_status_chk CHECK (
        api_key_status IN ('CONNECTED', 'DISCONNECTED', 'ERROR')
    ),
    CONSTRAINT accounting_integrations_freq_chk CHECK (
        sync_frequency IS NULL OR sync_frequency IN ('DAILY', 'WEEKLY')
    ),
    CONSTRAINT accounting_integrations_pharmacy_uq UNIQUE (pharmacy_id)
);

CREATE INDEX idx_accounting_integrations_next_sync
    ON accounting_integrations (next_sync_at)
    WHERE auto_sync_enabled = TRUE AND next_sync_at IS NOT NULL;

CREATE TABLE accounting_sync_jobs (
    id UUID PRIMARY KEY,
    pharmacy_id UUID NOT NULL REFERENCES pharmacies (id),
    accounting_system VARCHAR(15) NOT NULL,
    sync_type VARCHAR(15) NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    status VARCHAR(15) NOT NULL,
    records_processed INTEGER NOT NULL DEFAULT 0,
    records_synced INTEGER NOT NULL DEFAULT 0,
    records_failed INTEGER NOT NULL DEFAULT 0,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    triggered_by VARCHAR(10) NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT accounting_sync_jobs_system_chk CHECK (
        accounting_system IN ('TALLY', 'ZOHO_BOOKS')
    ),
    CONSTRAINT accounting_sync_jobs_type_chk CHECK (
        sync_type IN ('SALES', 'PURCHASES', 'EXPENSES', 'GST')
    ),
    CONSTRAINT accounting_sync_jobs_status_chk CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT accounting_sync_jobs_triggered_chk CHECK (
        triggered_by IN ('MANUAL', 'SCHEDULER')
    )
);

CREATE INDEX idx_accounting_sync_jobs_pharmacy_status
    ON accounting_sync_jobs (pharmacy_id, status);

CREATE INDEX idx_accounting_sync_jobs_queued
    ON accounting_sync_jobs (queued_at)
    WHERE status = 'QUEUED';
