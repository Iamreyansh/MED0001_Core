-- EPIC-014 / STORY-003: SaaS billing invoices + line items
-- Rollback:
--   DROP TABLE IF EXISTS saas_invoice_line_item;
--   DROP TABLE IF EXISTS saas_invoice;
--   DROP TABLE IF EXISTS saas_invoice_number_counter;
-- Notes: money as BIGINT paise. Invoice numbers NMM-INV-YYYY-MM-XXXXXX via monthly counter.
--        Line items immutable after insert (app-enforced). WAIVED excluded from overdue metrics.

CREATE TABLE IF NOT EXISTS saas_invoice_number_counter (
    year_month CHAR(7) PRIMARY KEY,
    last_seq INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS saas_invoice (
    id UUID PRIMARY KEY,
    invoice_number VARCHAR(30) NOT NULL UNIQUE,
    account_id UUID NOT NULL REFERENCES crm_account (id),
    subscription_id UUID NOT NULL REFERENCES saas_subscription (id),
    plan_name VARCHAR(32) NOT NULL,
    billing_period_from DATE NOT NULL,
    billing_period_to DATE NOT NULL,
    subtotal_paise BIGINT NOT NULL,
    gst_rate_pct NUMERIC(4, 2) NOT NULL DEFAULT 18.00,
    gst_amount_paise BIGINT NOT NULL,
    total_amount_paise BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DUE',
    due_at DATE NOT NULL,
    paid_at TIMESTAMPTZ,
    payment_mode VARCHAR(20),
    reference_number VARCHAR(100),
    marked_paid_by UUID,
    dunning_step INT NOT NULL DEFAULT 0,
    waive_reason TEXT,
    pdf_object_key VARCHAR(200),
    checkout_url TEXT,
    checkout_expires_at TIMESTAMPTZ,
    mark_paid_idempotency_key VARCHAR(128),
    pay_idempotency_key VARCHAR(128),
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT saas_invoice_status_chk CHECK (
        status IN ('PAID', 'DUE', 'OVERDUE', 'DUNNING', 'WAIVED')
    ),
    CONSTRAINT saas_invoice_dunning_chk CHECK (dunning_step BETWEEN 0 AND 4)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_saas_invoice_mark_paid_idem
    ON saas_invoice (mark_paid_idempotency_key)
    WHERE mark_paid_idempotency_key IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_saas_invoice_pay_idem
    ON saas_invoice (pay_idempotency_key)
    WHERE pay_idempotency_key IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_saas_invoice_account_created
    ON saas_invoice (account_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_saas_invoice_status_due
    ON saas_invoice (status, due_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_saas_invoice_period
    ON saas_invoice (billing_period_from, billing_period_to)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS saas_invoice_line_item (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES saas_invoice (id),
    description VARCHAR(200) NOT NULL,
    sac_code VARCHAR(10) NOT NULL DEFAULT '9983',
    amount_paise BIGINT NOT NULL,
    item_type VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT saas_invoice_line_type_chk CHECK (
        item_type IN ('PLAN', 'ADDON', 'CREDIT')
    )
);

CREATE INDEX IF NOT EXISTS idx_saas_invoice_line_invoice
    ON saas_invoice_line_item (invoice_id);
