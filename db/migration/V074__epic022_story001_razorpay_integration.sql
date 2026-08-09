-- EPIC-022 / STORY-001: Razorpay + RazorpayX integration records
-- Rollback:
--   DROP TABLE IF EXISTS razorpayx_payout_records;
--   DROP TABLE IF EXISTS razorpayx_fund_accounts;
--   DROP TABLE IF EXISTS razorpay_payment_records;
-- Notes: account numbers never stored (account_last4 only); amounts in paise.

CREATE TABLE razorpay_payment_records (
    id UUID PRIMARY KEY,
    platform_order_id UUID NOT NULL,
    razorpay_order_id VARCHAR(50) NOT NULL,
    razorpay_payment_id VARCHAR(50) NULL,
    amount_paise INTEGER NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    payment_method VARCHAR(20) NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    captured_at TIMESTAMPTZ NULL,
    CONSTRAINT razorpay_payment_records_status_chk CHECK (
        status IN ('created', 'authorized', 'captured', 'failed', 'refunded')
    ),
    CONSTRAINT razorpay_payment_records_payment_id_uq UNIQUE (razorpay_payment_id)
);

CREATE INDEX idx_razorpay_payment_records_order
    ON razorpay_payment_records (razorpay_order_id);

CREATE INDEX idx_razorpay_payment_records_platform_order
    ON razorpay_payment_records (platform_order_id);

CREATE TABLE razorpayx_fund_accounts (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(10) NOT NULL,
    entity_id UUID NOT NULL,
    razorpayx_contact_id VARCHAR(50) NOT NULL,
    fund_account_id VARCHAR(50) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    account_last4 VARCHAR(4) NOT NULL,
    ifsc VARCHAR(12) NOT NULL,
    account_holder_name VARCHAR(200) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT razorpayx_fund_accounts_entity_chk CHECK (
        entity_type IN ('PHARMACY', 'RIDER')
    ),
    CONSTRAINT razorpayx_fund_accounts_fa_uq UNIQUE (fund_account_id)
);

CREATE INDEX idx_razorpayx_fund_accounts_entity
    ON razorpayx_fund_accounts (entity_type, entity_id)
    WHERE is_active = TRUE;

CREATE TABLE razorpayx_payout_records (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(10) NOT NULL,
    entity_id UUID NOT NULL,
    fund_account_id VARCHAR(50) NOT NULL,
    razorpayx_payout_id VARCHAR(50) NULL,
    reference_id VARCHAR(100) NOT NULL,
    amount_paise BIGINT NOT NULL,
    mode VARCHAR(5) NOT NULL,
    status VARCHAR(15) NOT NULL,
    retry_count SMALLINT NOT NULL DEFAULT 0,
    initiated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ NULL,
    failure_reason TEXT NULL,
    CONSTRAINT razorpayx_payout_records_entity_chk CHECK (
        entity_type IN ('PHARMACY', 'RIDER')
    ),
    CONSTRAINT razorpayx_payout_records_mode_chk CHECK (
        mode IN ('IMPS', 'NEFT', 'UPI')
    ),
    CONSTRAINT razorpayx_payout_records_status_chk CHECK (
        status IN ('processing', 'processed', 'reversed', 'failed')
    ),
    CONSTRAINT razorpayx_payout_records_payout_id_uq UNIQUE (razorpayx_payout_id)
);

CREATE INDEX idx_razorpayx_payout_records_retry
    ON razorpayx_payout_records (status, retry_count, initiated_at)
    WHERE status = 'failed';
