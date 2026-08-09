-- EPIC-014 / STORY-005: Account health scoring + save plays
-- Rollback:
--   DROP TABLE IF EXISTS crm_save_play;
--   DROP TABLE IF EXISTS crm_account_health_snapshot;
--   DROP TABLE IF EXISTS crm_account_health_score;
-- Notes: overall_score = usage×0.30 + billing×0.25 + support×0.25 + business×0.20.
--        Bands: HEALTHY 75–100, MODERATE 50–74, AT_RISK 25–49, CHURNING 0–24.
--        Auto save-play notify on first drop below 40 (outbox ids-only).

CREATE TABLE IF NOT EXISTS crm_account_health_score (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES crm_account (id),
    overall_score NUMERIC(5, 2) NOT NULL,
    product_usage_score NUMERIC(5, 2) NOT NULL,
    billing_health_score NUMERIC(5, 2) NOT NULL,
    support_satisfaction_score NUMERIC(5, 2) NOT NULL,
    business_performance_score NUMERIC(5, 2) NOT NULL,
    health_band VARCHAR(16) NOT NULL,
    risk_factors TEXT[] NOT NULL DEFAULT '{}',
    recommended_actions TEXT[] NOT NULL DEFAULT '{}',
    computed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT crm_account_health_band_chk CHECK (
        health_band IN ('HEALTHY', 'MODERATE', 'AT_RISK', 'CHURNING')
    ),
    CONSTRAINT crm_account_health_score_range_chk CHECK (
        overall_score BETWEEN 0 AND 100
        AND product_usage_score BETWEEN 0 AND 100
        AND billing_health_score BETWEEN 0 AND 100
        AND support_satisfaction_score BETWEEN 0 AND 100
        AND business_performance_score BETWEEN 0 AND 100
    )
);

CREATE INDEX IF NOT EXISTS idx_crm_account_health_band_score
    ON crm_account_health_score (health_band, overall_score);

CREATE TABLE IF NOT EXISTS crm_account_health_snapshot (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    score_date DATE NOT NULL,
    overall_score NUMERIC(5, 2) NOT NULL,
    health_band VARCHAR(16) NOT NULL,
    product_usage_score NUMERIC(5, 2) NOT NULL,
    billing_health_score NUMERIC(5, 2) NOT NULL,
    support_satisfaction_score NUMERIC(5, 2) NOT NULL,
    business_performance_score NUMERIC(5, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_crm_account_health_snapshot UNIQUE (account_id, score_date),
    CONSTRAINT crm_health_snapshot_band_chk CHECK (
        health_band IN ('HEALTHY', 'MODERATE', 'AT_RISK', 'CHURNING')
    )
);

CREATE INDEX IF NOT EXISTS idx_crm_account_health_snapshot_date
    ON crm_account_health_snapshot (score_date DESC);

CREATE TABLE IF NOT EXISTS crm_save_play (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    action_type VARCHAR(32) NOT NULL,
    outcome TEXT NOT NULL,
    notes TEXT,
    logged_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT crm_save_play_action_chk CHECK (
        action_type IN (
            'CALL', 'EMAIL', 'TRAINING', 'DISCOUNT_OFFERED', 'PLAN_ADJUSTED'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_crm_save_play_account_created
    ON crm_save_play (account_id, created_at DESC);
