-- EPIC-013 / STORY-004: customer segments + memberships + snapshots + compute jobs
-- Rollback:
--   DROP INDEX IF EXISTS idx_segment_compute_jobs_queued;
--   DROP TABLE IF EXISTS segment_compute_jobs;
--   DROP INDEX IF EXISTS idx_segment_snapshots_date;
--   DROP TABLE IF EXISTS segment_snapshots;
--   DROP INDEX IF EXISTS idx_segment_memberships_customer;
--   DROP TABLE IF EXISTS segment_memberships;
--   DROP INDEX IF EXISTS idx_segments_type_active;
--   DROP INDEX IF EXISTS idx_segments_name_active;
--   DROP TABLE IF EXISTS segments;
-- Notes: money as BIGINT paise (API exposes *_rs); created_by → admin_staff; soft-delete via deleted_at on CUSTOM.

CREATE TABLE segments (
    id                UUID PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    description       TEXT,
    segment_type      VARCHAR(20) NOT NULL,
    criteria          JSONB,
    status            VARCHAR(30) NOT NULL DEFAULT 'READY',
    customer_count    INTEGER NOT NULL DEFAULT 0,
    avg_aov_paise     BIGINT,
    total_ltv_paise   BIGINT,
    last_computed_at  TIMESTAMPTZ,
    created_by        UUID REFERENCES admin_staff (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT chk_segments_type CHECK (segment_type IN ('SYSTEM', 'CUSTOM')),
    CONSTRAINT chk_segments_status CHECK (status IN ('PENDING_COMPUTE', 'READY', 'COMPUTING'))
);

CREATE UNIQUE INDEX idx_segments_name_active
    ON segments (LOWER(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_segments_type_active
    ON segments (segment_type)
    WHERE deleted_at IS NULL;

CREATE TABLE segment_memberships (
    segment_id   UUID NOT NULL REFERENCES segments (id),
    customer_id  UUID NOT NULL REFERENCES customers (id),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (segment_id, customer_id)
);

CREATE INDEX idx_segment_memberships_customer
    ON segment_memberships (customer_id);

CREATE TABLE segment_snapshots (
    segment_id     UUID NOT NULL REFERENCES segments (id),
    snapshot_date  DATE NOT NULL,
    customer_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (segment_id, snapshot_date)
);

CREATE INDEX idx_segment_snapshots_date
    ON segment_snapshots (snapshot_date);

CREATE TABLE segment_compute_jobs (
    id            UUID PRIMARY KEY,
    segment_id    UUID NOT NULL REFERENCES segments (id),
    status        VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT chk_segment_compute_jobs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_segment_compute_jobs_queued
    ON segment_compute_jobs (created_at)
    WHERE status = 'QUEUED';

-- Eight SYSTEM segments (fixed UUIDs for stable seeds).
INSERT INTO segments (id, name, description, segment_type, criteria, status, created_by) VALUES
(
    'a0130004-0000-4000-8000-000000000001',
    'NEW',
    'Customers with ≤1 order and account age under 7 days',
    'SYSTEM',
    '[{"field":"total_orders","operator":"<=","value":1},{"field":"account_age_days","operator":"<","value":7}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000002',
    'REGULAR',
    'Customers with 2–9 lifetime orders',
    'SYSTEM',
    '[{"field":"total_orders","operator":"between","value":[2,9]}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000003',
    'LOYAL',
    'Customers with 10–29 orders, or 3+ orders in the last 30 days',
    'SYSTEM',
    '[{"field":"total_orders","operator":"between","value":[10,29]}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000004',
    'VIP',
    '30+ orders or LTV > Rs 10,000',
    'SYSTEM',
    '[{"field":"total_orders","operator":">=","value":30}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000005',
    'DORMANT',
    'No order in the last 60+ days',
    'SYSTEM',
    '[{"field":"last_order_days_ago","operator":">=","value":60}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000006',
    'RX_USERS',
    'Customers who have placed at least one prescription order',
    'SYSTEM',
    '[{"field":"has_rx_orders","operator":"=","value":true}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000007',
    'HIGH_VALUE_AREA',
    'Delivery address in configured high-value pincodes',
    'SYSTEM',
    '[{"field":"pincode","operator":"in","value":[]}]'::jsonb,
    'READY',
    NULL
),
(
    'a0130004-0000-4000-8000-000000000008',
    'ALL',
    'All registered customers',
    'SYSTEM',
    '[]'::jsonb,
    'READY',
    NULL
);
