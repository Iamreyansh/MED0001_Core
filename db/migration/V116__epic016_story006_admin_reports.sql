-- EPIC-016 / STORY-006: admin report library (definitions, schedules, jobs)
-- Rollback: DROP TABLE IF EXISTS admin_report_jobs;
--           DROP TABLE IF EXISTS admin_report_schedules;
--           DROP TABLE IF EXISTS admin_report_definitions;
-- Notes: seed 14 report_ids; compliance retention 5y; schedules FK → admin_staff
--        (story text says admin_users). Jobs support MANUAL|SCHEDULED triggers.

CREATE TABLE admin_report_definitions (
    report_id         VARCHAR(60) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    category          VARCHAR(20) NOT NULL,
    description       TEXT NOT NULL,
    default_cadence   VARCHAR(15) NOT NULL,
    default_format    VARCHAR(5) NOT NULL,
    retention_years   SMALLINT NOT NULL,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_admin_report_category
        CHECK (category IN ('FINANCE', 'OPERATIONS', 'COMPLIANCE', 'GROWTH')),
    CONSTRAINT chk_admin_report_cadence
        CHECK (default_cadence IN ('DAILY', 'WEEKLY', 'MONTHLY', 'ON_DEMAND')),
    CONSTRAINT chk_admin_report_format
        CHECK (default_format IN ('CSV', 'PDF')),
    CONSTRAINT chk_admin_report_retention
        CHECK (retention_years IN (2, 5))
);

CREATE TABLE admin_report_schedules (
    id                 UUID PRIMARY KEY,
    report_id          VARCHAR(60) NOT NULL REFERENCES admin_report_definitions (report_id),
    is_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    cadence            VARCHAR(15) NOT NULL,
    format             VARCHAR(5) NOT NULL,
    email_recipients   TEXT[] NOT NULL DEFAULT '{}',
    next_run_at        TIMESTAMPTZ,
    updated_by         UUID REFERENCES admin_staff (id),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_admin_report_schedules_report UNIQUE (report_id),
    CONSTRAINT chk_admin_report_sched_cadence
        CHECK (cadence IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT chk_admin_report_sched_format
        CHECK (format IN ('CSV', 'PDF'))
);

CREATE INDEX idx_admin_report_schedules_due
    ON admin_report_schedules (next_run_at)
    WHERE is_enabled = TRUE;

CREATE TABLE admin_report_jobs (
    id              UUID PRIMARY KEY,
    report_id       VARCHAR(60) NOT NULL REFERENCES admin_report_definitions (report_id),
    triggered_by    UUID,
    trigger_type    VARCHAR(10) NOT NULL,
    period_from     DATE NOT NULL,
    period_to       DATE NOT NULL,
    filters         JSONB NOT NULL DEFAULT '{}',
    format          VARCHAR(5) NOT NULL DEFAULT 'CSV',
    status          VARCHAR(15) NOT NULL,
    progress_pct    SMALLINT NOT NULL DEFAULT 0,
    row_count       INTEGER,
    file_size_kb    INTEGER,
    s3_key          VARCHAR(500),
    download_url    TEXT,
    expires_at      TIMESTAMPTZ,
    queued_at       TIMESTAMPTZ NOT NULL,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    CONSTRAINT chk_admin_report_jobs_trigger
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT chk_admin_report_jobs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_admin_report_jobs_format
        CHECK (format IN ('CSV', 'PDF')),
    CONSTRAINT chk_admin_report_jobs_progress
        CHECK (progress_pct BETWEEN 0 AND 100)
);

CREATE INDEX idx_admin_report_jobs_status_queued
    ON admin_report_jobs (status, queued_at);

CREATE INDEX idx_admin_report_jobs_triggered_status
    ON admin_report_jobs (triggered_by, status);

CREATE INDEX idx_admin_report_jobs_completed
    ON admin_report_jobs (completed_at DESC NULLS LAST);

INSERT INTO admin_report_definitions (
    report_id, name, category, description, default_cadence, default_format, retention_years, is_active
) VALUES
    ('GMV_COMMISSION_PAYOUTS',
     'GMV, Commission & Payouts Summary',
     'FINANCE',
     'Platform-wide GMV, commission earned, and rider/pharmacy payouts for the period.',
     'MONTHLY', 'CSV', 2, TRUE),
    ('TAX_GSTR8_PREP',
     'GSTR-8 TCS Preparation',
     'FINANCE',
     'Marketplace TCS register export for GSTR-8 filing; reconciles to commission/TCS ledger at ₹0 tolerance.',
     'MONTHLY', 'CSV', 2, TRUE),
    ('PLATFORM_PNL',
     'Platform P&L Summary',
     'FINANCE',
     'Platform profit and loss summary for the selected period.',
     'MONTHLY', 'CSV', 2, TRUE),
    ('REFUND_SUMMARY',
     'Refund Summary',
     'FINANCE',
     'Refunds processed in the period by reason and payment method.',
     'MONTHLY', 'CSV', 2, TRUE),
    ('SETTLEMENT_SUMMARY',
     'Settlement Summary',
     'FINANCE',
     'Pharmacy and rider settlement totals for the period.',
     'MONTHLY', 'CSV', 2, TRUE),
    ('ORDER_FULFILMENT',
     'Order Fulfilment Report',
     'OPERATIONS',
     'Order fulfilment funnel and stage timings for the period.',
     'WEEKLY', 'CSV', 2, TRUE),
    ('SLA_BREACHES',
     'SLA Breaches Report',
     'OPERATIONS',
     'Orders that breached delivery SLA in the period.',
     'DAILY', 'CSV', 2, TRUE),
    ('CANCELLATION_ANALYSIS',
     'Cancellation Analysis',
     'OPERATIONS',
     'Cancellation reasons and counts for the period.',
     'WEEKLY', 'CSV', 2, TRUE),
    ('RIDER_PERFORMANCE',
     'Rider Performance Report',
     'OPERATIONS',
     'Rider delivery counts, on-time rate, and earnings for the period.',
     'WEEKLY', 'CSV', 2, TRUE),
    ('COMPLIANCE_SCHEDULE_H',
     'Schedule H/H1 Prescription Register',
     'COMPLIANCE',
     'All Schedule H and H1 prescription medicine sales with prescription and identity references.',
     'ON_DEMAND', 'PDF', 5, TRUE),
    ('SCHEDULE_X_REGISTER',
     'Schedule X Register',
     'COMPLIANCE',
     'Schedule X controlled substance dispense register for Drug Inspector audit.',
     'ON_DEMAND', 'PDF', 5, TRUE),
    ('DRUG_RECALL_IMPACT',
     'Drug Recall Impact',
     'COMPLIANCE',
     'Orders and inventory lots impacted by drug recall actions.',
     'ON_DEMAND', 'CSV', 5, TRUE),
    ('COHORT_RETENTION',
     'Cohort Retention Report',
     'GROWTH',
     'Weekly cohort retention table for the last 12 cohorts.',
     'WEEKLY', 'CSV', 2, TRUE),
    ('ACQUISITION_MIX',
     'Acquisition Mix Report',
     'GROWTH',
     'New customer acquisition mix by source for the period.',
     'WEEKLY', 'CSV', 2, TRUE);
