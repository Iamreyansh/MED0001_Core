-- EPIC-008 / STORY-006: regulatory compliance filings + generate jobs
-- Rollback:
--   DROP INDEX IF EXISTS uq_compliance_filing_generate_active;
--   DROP INDEX IF EXISTS idx_compliance_filing_generate_filing;
--   DROP TABLE IF EXISTS compliance_filing_generate_job;
--   DROP INDEX IF EXISTS idx_compliance_filing_status_due;
--   DROP INDEX IF EXISTS idx_compliance_filing_type_period;
--   DROP TABLE IF EXISTS compliance_filing;
--   DROP INDEX IF EXISTS idx_compliance_activity_log_created;
--   DROP INDEX IF EXISTS idx_compliance_activity_log_action;
-- Notes: Monthly H1/X calendar auto-created; ADE/DRUG_RECALL listed/tracked.
--        Activity log actions are free TEXT (shared V082); STORY-006 adds
--        FILING_GENERATED, FILING_MARKED, DRUG_RECALLED (and DOCTOR_BLACKLISTED upstream).

CREATE TABLE compliance_filing (
    id                        UUID PRIMARY KEY,
    filing_type               VARCHAR(40) NOT NULL,
    period_from               DATE NOT NULL,
    period_to                 DATE NOT NULL,
    due_date                  DATE NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    generated_report_s3_key   VARCHAR(512),
    generated_report_format   VARCHAR(10),
    generated_at              TIMESTAMPTZ,
    filed_by                  UUID,
    filed_at                  TIMESTAMPTZ,
    reference_number          VARCHAR(128),
    is_archived               BOOLEAN NOT NULL DEFAULT FALSE,
    overdue_alerted_at        TIMESTAMPTZ,
    overdue_escalation_at     TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_compliance_filing_type CHECK (filing_type IN (
        'SCHEDULE_H1_REGISTER',
        'SCHEDULE_X_REGISTER',
        'ADVERSE_EVENTS',
        'DRUG_RECALL'
    )),
    CONSTRAINT chk_compliance_filing_status CHECK (status IN (
        'PENDING',
        'FILED',
        'OVERDUE'
    )),
    CONSTRAINT chk_compliance_filing_format CHECK (
        generated_report_format IS NULL
        OR generated_report_format IN ('CSV', 'PDF')
    )
);

-- Monthly H1/X calendar only (ADE/DRUG_RECALL may repeat).
CREATE UNIQUE INDEX uq_compliance_filing_type_period
    ON compliance_filing (filing_type, period_from, period_to)
    WHERE filing_type IN ('SCHEDULE_H1_REGISTER', 'SCHEDULE_X_REGISTER');

CREATE INDEX idx_compliance_filing_type_period
    ON compliance_filing (filing_type, period_from DESC);

CREATE INDEX idx_compliance_filing_status_due
    ON compliance_filing (status, due_date)
    WHERE is_archived = FALSE;

CREATE TABLE compliance_filing_generate_job (
    id            UUID PRIMARY KEY,
    filing_id     UUID NOT NULL REFERENCES compliance_filing (id),
    format        VARCHAR(10) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    storage_key   VARCHAR(512),
    row_count     INTEGER,
    requested_by  UUID NOT NULL,
    generated_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    error_message VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_compliance_filing_generate_format CHECK (format IN ('CSV', 'PDF')),
    CONSTRAINT chk_compliance_filing_generate_status CHECK (status IN (
        'GENERATING',
        'READY',
        'FAILED'
    ))
);

CREATE INDEX idx_compliance_filing_generate_filing
    ON compliance_filing_generate_job (filing_id, created_at DESC);

-- Concurrent generate dedupe: at most one GENERATING job per filing.
CREATE UNIQUE INDEX uq_compliance_filing_generate_active
    ON compliance_filing_generate_job (filing_id)
    WHERE status = 'GENERATING';

CREATE INDEX idx_compliance_activity_log_created
    ON compliance_activity_log (created_at DESC);

CREATE INDEX idx_compliance_activity_log_action
    ON compliance_activity_log (action, created_at DESC);
