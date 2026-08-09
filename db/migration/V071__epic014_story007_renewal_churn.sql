-- EPIC-014 / STORY-007: Renewal pipeline + churn survey / analysis
-- Rollback:
--   DROP TABLE IF EXISTS crm_churn_survey;
--   DROP TABLE IF EXISTS saas_subscription_cohort;
-- Notes: cohort keyed by account_id (first paid subscribe month).
--        logo_churn_pct = (churned / start_logos) × 100.
--        Win-back / at-risk CSM alerts via outbox (ids-only).

CREATE TABLE IF NOT EXISTS crm_churn_survey (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    reason VARCHAR(32) NOT NULL,
    notes TEXT,
    logged_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT crm_churn_survey_reason_chk CHECK (
        reason IN (
            'PRICE',
            'FEATURES',
            'MOVING_TO_COMPETITOR',
            'CLOSING_BUSINESS',
            'NOT_USING',
            'OTHER'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_crm_churn_survey_account_created
    ON crm_churn_survey (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_crm_churn_survey_created
    ON crm_churn_survey (created_at DESC);

CREATE TABLE IF NOT EXISTS saas_subscription_cohort (
    account_id UUID PRIMARY KEY REFERENCES crm_account (id),
    cohort_month DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_saas_subscription_cohort_month
    ON saas_subscription_cohort (cohort_month);

-- Backfill cohort from existing non-FREE subscriptions (first created_at month, UTC).
INSERT INTO saas_subscription_cohort (account_id, cohort_month, created_at)
SELECT
    s.account_id,
    date_trunc('month', s.created_at AT TIME ZONE 'UTC')::date,
    NOW()
FROM saas_subscription s
JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
WHERE s.deleted_at IS NULL
  AND p.name <> 'FREE'
  AND NOT EXISTS (
      SELECT 1 FROM saas_subscription_cohort c WHERE c.account_id = s.account_id
  )
ON CONFLICT (account_id) DO NOTHING;
