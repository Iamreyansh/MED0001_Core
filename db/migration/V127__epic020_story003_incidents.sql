-- EPIC-020 / STORY-003: SLO & incident management
-- Rollback:
--   DROP TABLE IF EXISTS monitoring_incidents;
--   ALTER TABLE slo_compliance_history DROP COLUMN IF EXISTS incident_count;
-- Notes: impacted_gmv stored as BIGINT paise (API exposes impacted_gmv_rs);
--   created_by → admin_staff (null = SYSTEM); source_alert_id dedups auto-create;
--   postmortem_reminder_sent_at prevents reminder spam.

CREATE TABLE monitoring_incidents (
    id                          UUID PRIMARY KEY,
    incident_number             VARCHAR(20)  NOT NULL,
    title                       TEXT         NOT NULL,
    severity                    VARCHAR(3)   NOT NULL,
    description                 TEXT         NOT NULL,
    status                      VARCHAR(15)  NOT NULL,
    affected_services           TEXT[]       NOT NULL DEFAULT '{}',
    impacted_metrics            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    impacted_gmv_paise          BIGINT       NOT NULL DEFAULT 0,
    root_cause                  TEXT,
    fix_applied                 TEXT,
    prevention_steps            TEXT,
    postmortem_filed            BOOLEAN      NOT NULL DEFAULT FALSE,
    postmortem_deadline         TIMESTAMPTZ,
    postmortem_reminder_sent_at TIMESTAMPTZ,
    detected_at                 TIMESTAMPTZ  NOT NULL,
    resolved_at                 TIMESTAMPTZ,
    duration_minutes            INTEGER,
    created_by                  UUID REFERENCES admin_staff (id),
    source_alert_id             UUID REFERENCES monitoring_alerts (id),
    status_history              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT uq_monitoring_incidents_number UNIQUE (incident_number),
    CONSTRAINT uq_monitoring_incidents_source_alert UNIQUE (source_alert_id),
    CONSTRAINT chk_monitoring_incidents_severity
        CHECK (severity IN ('P1', 'P2', 'P3')),
    CONSTRAINT chk_monitoring_incidents_status
        CHECK (status IN ('DETECTED', 'INVESTIGATING', 'MITIGATING', 'RESOLVED'))
);

CREATE INDEX idx_monitoring_incidents_list
    ON monitoring_incidents (status, severity, detected_at DESC);

CREATE INDEX idx_monitoring_incidents_postmortem
    ON monitoring_incidents (resolved_at)
    WHERE postmortem_filed = FALSE AND severity IN ('P1', 'P2');

ALTER TABLE slo_compliance_history
    ADD COLUMN incident_count INTEGER NOT NULL DEFAULT 0;
