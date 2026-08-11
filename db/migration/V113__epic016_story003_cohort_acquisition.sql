-- EPIC-016 / STORY-003: growth & cohort analytics
-- Rollback: DROP TABLE IF EXISTS campaign_spend;
--           DROP TABLE IF EXISTS analytics_acquisition_daily;
--           DROP TABLE IF EXISTS analytics_cohort_retention;
-- Notes: cohort_week ISO e.g. 2026-W17; retention precomputed Sunday 03:00 IST;
--        money BIGINT paise on acquisition; campaign spend in INR (rs);
--        entered_by → admin_staff (story text says admin_users).

CREATE TABLE analytics_cohort_retention (
    id              UUID PRIMARY KEY,
    cohort_week     VARCHAR(8) NOT NULL,
    cohort_size     INTEGER NOT NULL DEFAULT 0,
    elapsed_week    INTEGER NOT NULL,
    retained_count  INTEGER NOT NULL DEFAULT 0,
    retention_pct   DECIMAL(5, 2) NOT NULL DEFAULT 0,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analytics_cohort_retention UNIQUE (cohort_week, elapsed_week),
    CONSTRAINT chk_analytics_cohort_elapsed
        CHECK (elapsed_week >= 0 AND elapsed_week <= 52)
);

CREATE INDEX idx_analytics_cohort_retention_week
    ON analytics_cohort_retention (cohort_week);

CREATE TABLE analytics_acquisition_daily (
    id              UUID PRIMARY KEY,
    snapshot_date   DATE NOT NULL,
    source          VARCHAR(20) NOT NULL,
    new_users       INTEGER NOT NULL DEFAULT 0,
    orders          INTEGER NOT NULL DEFAULT 0,
    gmv_paise       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_analytics_acquisition_daily UNIQUE (snapshot_date, source),
    CONSTRAINT chk_analytics_acquisition_source
        CHECK (source IN ('ORGANIC', 'REFERRAL', 'AD', 'PARTNER'))
);

CREATE INDEX idx_analytics_acquisition_daily_date
    ON analytics_acquisition_daily (snapshot_date);

CREATE TABLE campaign_spend (
    id              UUID PRIMARY KEY,
    source          VARCHAR(20) NOT NULL,
    spend_rs        DECIMAL(12, 2) NOT NULL DEFAULT 0,
    period_from     DATE NOT NULL,
    period_to       DATE NOT NULL,
    entered_by      UUID NOT NULL REFERENCES admin_staff (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_campaign_spend_source
        CHECK (source IN ('AD', 'PARTNER', 'REFERRAL')),
    CONSTRAINT chk_campaign_spend_period
        CHECK (period_from <= period_to)
);

CREATE INDEX idx_campaign_spend_period
    ON campaign_spend (period_from, period_to);
