-- EPIC-014 / STORY-002: SaaS subscription lifecycle
-- Rollback:
--   DROP TABLE IF EXISTS saas_subscription;
-- Notes: money remains on saas_plan (paise). One subscription per crm_account.
--        Backfill ACTIVE FREE (or current_plan_name) rows for existing accounts.
--        SoT is saas_subscription; crm_account.current_plan_name/status kept in sync.

CREATE TABLE IF NOT EXISTS saas_subscription (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES crm_account (id),
    plan_id UUID NOT NULL REFERENCES saas_plan (id),
    scheduled_plan_id UUID REFERENCES saas_plan (id),
    status VARCHAR(32) NOT NULL,
    billing_cycle VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    renewal_date TIMESTAMPTZ NOT NULL,
    trial_ends_at TIMESTAMPTZ,
    auto_renew BOOLEAN NOT NULL DEFAULT TRUE,
    cancelled_at TIMESTAMPTZ,
    cancels_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    past_due_at TIMESTAMPTZ,
    last_invoice_id UUID,
    override_plan_id UUID REFERENCES saas_plan (id),
    override_expires_at TIMESTAMPTZ,
    override_reason TEXT,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT saas_subscription_status_chk CHECK (
        status IN ('ACTIVE', 'TRIAL', 'PAST_DUE', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT saas_subscription_cycle_chk CHECK (
        billing_cycle IN ('MONTHLY', 'ANNUAL')
    )
);

CREATE INDEX IF NOT EXISTS idx_saas_subscription_status_renewal
    ON saas_subscription (status, renewal_date)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_saas_subscription_past_due
    ON saas_subscription (past_due_at)
    WHERE deleted_at IS NULL AND status = 'PAST_DUE';

-- One ACTIVE subscription per existing CRM account from denormalised plan name.
INSERT INTO saas_subscription (
    id, account_id, plan_id, status, billing_cycle, renewal_date,
    auto_renew, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    a.id,
    p.id,
    COALESCE(NULLIF(a.status, ''), 'ACTIVE'),
    'MONTHLY',
    NOW() + INTERVAL '1 month',
    TRUE,
    NOW(),
    NOW()
FROM crm_account a
JOIN saas_plan p ON p.name = a.current_plan_name AND p.deleted_at IS NULL
WHERE a.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM saas_subscription s WHERE s.account_id = a.id AND s.deleted_at IS NULL
  );
