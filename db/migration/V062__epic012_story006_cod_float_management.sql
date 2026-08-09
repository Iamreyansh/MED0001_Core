-- EPIC-012 / STORY-006: COD float management (finance side)
-- Rollback:
--   DROP TABLE IF EXISTS cod_reconciliation_report;
-- Notes: Money as BIGINT paise. Reuses V041 cod_collections/cod_deposits.
--        Rider breakdown stored as JSONB for report GET + CSV export.
--        reconciliation_status PENDING = job claimed/running (JOB_ALREADY_RUNNING).

CREATE TABLE cod_reconciliation_report (
    id                          UUID PRIMARY KEY,
    report_date                 DATE         NOT NULL,
    total_cod_orders            INTEGER      NOT NULL DEFAULT 0,
    total_cod_amount_paise      BIGINT       NOT NULL DEFAULT 0,
    collected_by_riders_paise   BIGINT       NOT NULL DEFAULT 0,
    deposited_to_platform_paise BIGINT       NOT NULL DEFAULT 0,
    outstanding_float_paise     BIGINT       NOT NULL DEFAULT 0,
    variance_paise              BIGINT       NOT NULL DEFAULT 0,
    variance_reason             TEXT,
    reconciliation_status       VARCHAR(32)  NOT NULL,
    alert_sent                  BOOLEAN      NOT NULL DEFAULT FALSE,
    generated_at                TIMESTAMPTZ  NOT NULL,
    triggered_by                UUID,
    rider_breakdown_json        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at                  TIMESTAMPTZ,
    CONSTRAINT chk_cod_recon_status CHECK (
        reconciliation_status IN ('BALANCED', 'DISCREPANCY', 'PENDING')
    )
);

CREATE UNIQUE INDEX uq_cod_reconciliation_report_date_active
    ON cod_reconciliation_report (report_date)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_cod_reconciliation_report_status
    ON cod_reconciliation_report (reconciliation_status)
    WHERE deleted_at IS NULL;
