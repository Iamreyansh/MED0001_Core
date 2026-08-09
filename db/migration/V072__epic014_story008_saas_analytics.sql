-- EPIC-014 / STORY-008: SaaS revenue analytics cache + cohort retention + S&M spend
-- Rollback:
--   DROP TABLE IF EXISTS saas_cohort_retention;
--   DROP TABLE IF EXISTS saas_metrics_cache;
--   DROP TABLE IF EXISTS saas_sm_spend;
-- Notes: money as BIGINT paise; API exposes *_rs. Monthly batch + compute-on-miss.

CREATE TABLE IF NOT EXISTS saas_sm_spend (
    period_month DATE PRIMARY KEY,
    amount_paise BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT saas_sm_spend_amount_chk CHECK (amount_paise >= 0)
);

CREATE TABLE IF NOT EXISTS saas_metrics_cache (
    metric_month DATE PRIMARY KEY,
    mrr_paise BIGINT NOT NULL,
    arr_paise BIGINT NOT NULL,
    arpa_paise BIGINT NOT NULL,
    nrr_pct NUMERIC(8, 2) NOT NULL,
    grr_pct NUMERIC(8, 2) NOT NULL,
    quick_ratio NUMERIC(8, 2) NOT NULL,
    magic_number NUMERIC(8, 2),
    ltv_paise BIGINT NOT NULL,
    cac_paise BIGINT NOT NULL,
    logo_churn_pct NUMERIC(8, 2) NOT NULL,
    start_mrr_paise BIGINT NOT NULL,
    new_mrr_paise BIGINT NOT NULL,
    expansion_mrr_paise BIGINT NOT NULL,
    contraction_mrr_paise BIGINT NOT NULL,
    churn_mrr_paise BIGINT NOT NULL,
    net_new_mrr_paise BIGINT NOT NULL,
    new_logos INT NOT NULL,
    churned_logos INT NOT NULL,
    expansion_accounts INT NOT NULL DEFAULT 0,
    contraction_accounts INT NOT NULL DEFAULT 0,
    computed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT saas_metrics_cache_mrr_chk CHECK (mrr_paise >= 0),
    CONSTRAINT saas_metrics_cache_logos_chk CHECK (new_logos >= 0 AND churned_logos >= 0)
);

CREATE TABLE IF NOT EXISTS saas_cohort_retention (
    cohort_month DATE NOT NULL,
    months_since INT NOT NULL,
    starting_accounts INT NOT NULL,
    retained_accounts INT NOT NULL,
    retention_pct NUMERIC(5, 2) NOT NULL,
    PRIMARY KEY (cohort_month, months_since),
    CONSTRAINT saas_cohort_retention_months_chk CHECK (months_since >= 0),
    CONSTRAINT saas_cohort_retention_counts_chk CHECK (
        starting_accounts >= 0 AND retained_accounts >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_saas_cohort_retention_cohort
    ON saas_cohort_retention (cohort_month);

-- Sample S&M spend for magic number / CAC (manual v1 seed).
INSERT INTO saas_sm_spend (period_month, amount_paise, updated_at)
VALUES (DATE '2026-04-01', 50000000, NOW())
ON CONFLICT (period_month) DO NOTHING;

INSERT INTO saas_sm_spend (period_month, amount_paise, updated_at)
VALUES (DATE '2026-05-01', 52000000, NOW())
ON CONFLICT (period_month) DO NOTHING;

INSERT INTO saas_sm_spend (period_month, amount_paise, updated_at)
VALUES (DATE '2026-06-01', 48000000, NOW())
ON CONFLICT (period_month) DO NOTHING;
