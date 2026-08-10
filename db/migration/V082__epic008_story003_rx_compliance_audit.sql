-- EPIC-008 / STORY-003: admin Rx compliance audit
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_compliance_activity_log_no_update ON compliance_activity_log;
--   DROP TRIGGER IF EXISTS trg_compliance_activity_log_no_delete ON compliance_activity_log;
--   DROP FUNCTION IF EXISTS compliance_activity_log_reject_mutation();
--   DROP INDEX IF EXISTS idx_rx_audit_entry_deadline;
--   DROP INDEX IF EXISTS idx_rx_audit_entry_schedule_status_deadline;
--   DROP INDEX IF EXISTS idx_rx_audit_entry_rx;
--   DROP TABLE IF EXISTS compliance_activity_log;
--   DROP TABLE IF EXISTS rx_audit_entry;
-- Notes: H1/X audit deadline = dispensed_at+24h; H = +7d. Activity log append-only
--        (shared with STORY-006). order_id nullable when dispense has no order.

CREATE TABLE rx_audit_entry (
    id                       UUID PRIMARY KEY,
    rx_id                    UUID NOT NULL REFERENCES prescription (id),
    order_id                 UUID NULL REFERENCES orders (id),
    pharmacy_id              UUID NOT NULL REFERENCES pharmacies (id),
    schedule                 VARCHAR(10) NOT NULL,
    audit_status             VARCHAR(30) NOT NULL,
    audit_deadline           TIMESTAMPTZ NOT NULL,
    possible_duplicate       BOOLEAN NOT NULL DEFAULT FALSE,
    possible_duplicate_rx_id UUID NULL REFERENCES prescription (id),
    verified_by              UUID NULL,
    verified_at              TIMESTAMPTZ NULL,
    flag_reason              VARCHAR(500) NULL,
    flag_severity           VARCHAR(10) NULL,
    flagged_by               UUID NULL,
    flagged_at               TIMESTAMPTZ NULL,
    notes                    VARCHAR(1000) NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rx_audit_entry_rx UNIQUE (rx_id),
    CONSTRAINT chk_rx_audit_schedule CHECK (schedule IN ('H', 'H1', 'X', 'NONE')),
    CONSTRAINT chk_rx_audit_status CHECK (audit_status IN (
        'AWAITING_AUDIT',
        'VERIFIED',
        'FLAGGED',
        'OVERDUE_AUDIT'
    )),
    CONSTRAINT chk_rx_audit_flag_severity CHECK (
        flag_severity IS NULL OR flag_severity IN ('LOW', 'MEDIUM', 'HIGH')
    )
);

CREATE INDEX idx_rx_audit_entry_schedule_status_deadline
    ON rx_audit_entry (schedule, audit_status, audit_deadline);

CREATE INDEX idx_rx_audit_entry_deadline
    ON rx_audit_entry (audit_deadline)
    WHERE audit_status = 'AWAITING_AUDIT';

CREATE INDEX idx_rx_audit_entry_rx
    ON rx_audit_entry (rx_id);

-- Append-only compliance activity log (STORY-003 + STORY-006).
CREATE TABLE compliance_activity_log (
    id         UUID PRIMARY KEY,
    rx_id      UUID NULL REFERENCES prescription (id),
    action     VARCHAR(40) NOT NULL,
    actor_id   UUID NOT NULL,
    actor_role VARCHAR(40) NOT NULL,
    payload    JSONB NULL,
    doctor_id  UUID NULL,
    filing_id  UUID NULL,
    ip_address VARCHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_compliance_activity_log_rx_created
    ON compliance_activity_log (rx_id, created_at DESC);

CREATE OR REPLACE FUNCTION compliance_activity_log_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'compliance_activity_log is append-only'
    USING ERRCODE = 'check_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_compliance_activity_log_no_update ON compliance_activity_log;
CREATE TRIGGER trg_compliance_activity_log_no_update
  BEFORE UPDATE ON compliance_activity_log
  FOR EACH ROW
  EXECUTE FUNCTION compliance_activity_log_reject_mutation();

DROP TRIGGER IF EXISTS trg_compliance_activity_log_no_delete ON compliance_activity_log;
CREATE TRIGGER trg_compliance_activity_log_no_delete
  BEFORE DELETE ON compliance_activity_log
  FOR EACH ROW
  EXECUTE FUNCTION compliance_activity_log_reject_mutation();
