-- EPIC-020 / STORY-001: realtime monitoring & alerting
-- Rollback:
--   DROP TABLE IF EXISTS metric_samples;
--   DROP TABLE IF EXISTS slo_compliance_history;
--   DROP TABLE IF EXISTS slo_definitions;
--   DROP TABLE IF EXISTS monitoring_alerts;
-- Notes: metrics store V1 = Postgres metric_samples @ 60s (not TimescaleDB);
--   acknowledged_by → admin_staff; money metrics as BIGINT paise in API (gmv_*).

CREATE TABLE monitoring_alerts (
    id                  UUID PRIMARY KEY,
    severity            VARCHAR(10)  NOT NULL,
    type                VARCHAR(30)  NOT NULL,
    message             TEXT         NOT NULL,
    triggering_metric   VARCHAR(60),
    triggering_value    DECIMAL(10, 4),
    threshold_value     DECIMAL(10, 4),
    zone_id             UUID,
    triggered_at        TIMESTAMPTZ  NOT NULL,
    acknowledged        BOOLEAN      NOT NULL DEFAULT FALSE,
    acknowledged_by     UUID REFERENCES admin_staff (id),
    acknowledged_at     TIMESTAMPTZ,
    acknowledged_notes  TEXT,
    auto_remediated     BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_at         TIMESTAMPTZ,
    resolution_reason   VARCHAR(50),
    CONSTRAINT chk_monitoring_alerts_severity
        CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_monitoring_alerts_type
        CHECK (type IN (
            'GMV_DROP',
            'DISPATCH_FAILURE',
            'ZONE_DARK',
            'PAYOUT_SPIKE',
            'PAYMENT_FAILURE',
            'SLA_BREACH_RATE',
            'SLO_ERROR_BUDGET_EXHAUSTED'
        ))
);

CREATE INDEX idx_monitoring_alerts_status
    ON monitoring_alerts (resolved_at, acknowledged, severity, triggered_at DESC);

-- Partial unique: one open alert per (type, zone); NULL zone → sentinel UUID
CREATE UNIQUE INDEX uq_monitoring_alerts_active_dedup
    ON monitoring_alerts (
        type,
        (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'::uuid))
    )
    WHERE resolved_at IS NULL;

CREATE TABLE slo_definitions (
    slo_name                  VARCHAR(60) PRIMARY KEY,
    description               TEXT         NOT NULL,
    target_pct                DECIMAL(5, 2) NOT NULL,
    metric_name               VARCHAR(60)  NOT NULL,
    measurement_window_days   INTEGER      NOT NULL DEFAULT 30
);

CREATE TABLE slo_compliance_history (
    id                          UUID PRIMARY KEY,
    slo_name                    VARCHAR(60) NOT NULL REFERENCES slo_definitions (slo_name),
    period_from                 DATE        NOT NULL,
    period_to                   DATE        NOT NULL,
    target_pct                  DECIMAL(5, 2) NOT NULL,
    actual_pct                  DECIMAL(5, 2) NOT NULL,
    compliant                   BOOLEAN     NOT NULL,
    error_budget_consumed_pct   DECIMAL(8, 2) NOT NULL,
    recorded_at                 TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_slo_compliance_history_slo
    ON slo_compliance_history (slo_name, period_to DESC);

-- ponytail: ceiling = single Postgres; upgrade → TimescaleDB hypertable
CREATE TABLE metric_samples (
    id            UUID PRIMARY KEY,
    metric_name   VARCHAR(60)  NOT NULL,
    bucket_at     TIMESTAMPTZ  NOT NULL,
    value         NUMERIC,
    zone_id       UUID
);

CREATE UNIQUE INDEX uq_metric_samples_bucket
    ON metric_samples (
        metric_name,
        bucket_at,
        (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'::uuid))
    );

CREATE INDEX idx_metric_samples_query
    ON metric_samples (metric_name, bucket_at DESC);

CREATE INDEX idx_metric_samples_zone
    ON metric_samples (metric_name, zone_id, bucket_at DESC)
    WHERE zone_id IS NOT NULL;

INSERT INTO slo_definitions (slo_name, description, target_pct, metric_name, measurement_window_days)
VALUES
    ('order_sla_adherence', '95% of orders delivered within 45 minutes', 95.00, 'sla_pct', 30),
    ('payment_success', '99% of payment captures succeed', 99.00, 'payment_success_pct', 30),
    ('dispatch_success', '98% of orders assigned within 10 minutes', 98.00, 'dispatch_rate', 30),
    ('api_p99_latency', 'API P99 latency < 500ms', 100.00, 'api_p99_compliance_pct', 30);
