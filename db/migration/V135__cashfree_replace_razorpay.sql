-- EPIC-022 / STORY-001: Razorpay → Cashfree rename (tables + gateway columns)
-- Rollback:
--   Reverse each RENAME below (cashfree_* → razorpay_*; gateway_* → razorpay_*).
-- Notes: historical V074 created razorpay_*; amounts remain paise.

-- Integration payment records
ALTER TABLE razorpay_payment_records RENAME TO cashfree_payment_records;
ALTER TABLE cashfree_payment_records RENAME COLUMN razorpay_order_id TO cashfree_order_id;
ALTER TABLE cashfree_payment_records RENAME COLUMN razorpay_payment_id TO cashfree_payment_id;
ALTER INDEX IF EXISTS idx_razorpay_payment_records_order RENAME TO idx_cashfree_payment_records_order;
ALTER INDEX IF EXISTS idx_razorpay_payment_records_platform_order RENAME TO idx_cashfree_payment_records_platform_order;
COMMENT ON TABLE cashfree_payment_records IS 'Cashfree PG order/payment audit (ex razorpay_payment_records)';

-- Beneficiaries (ex RazorpayX fund accounts)
ALTER TABLE razorpayx_fund_accounts RENAME TO cashfree_beneficiaries;
ALTER TABLE cashfree_beneficiaries RENAME COLUMN razorpayx_contact_id TO cashfree_contact_id;
ALTER TABLE cashfree_beneficiaries RENAME COLUMN fund_account_id TO beneficiary_id;
ALTER INDEX IF EXISTS idx_razorpayx_fund_accounts_entity RENAME TO idx_cashfree_beneficiaries_entity;
COMMENT ON TABLE cashfree_beneficiaries IS 'Cashfree Payouts beneficiaries (ex razorpayx_fund_accounts)';

-- Payout records
ALTER TABLE razorpayx_payout_records RENAME TO cashfree_payout_records;
ALTER TABLE cashfree_payout_records RENAME COLUMN fund_account_id TO beneficiary_id;
ALTER TABLE cashfree_payout_records RENAME COLUMN razorpayx_payout_id TO cashfree_transfer_id;
ALTER INDEX IF EXISTS idx_razorpayx_payout_records_retry RENAME TO idx_cashfree_payout_records_retry;
COMMENT ON TABLE cashfree_payout_records IS 'Cashfree Payouts transfer audit (ex razorpayx_payout_records)';

-- Orders / payment / refund — provider-neutral gateway_* columns
ALTER TABLE orders RENAME COLUMN razorpay_order_id TO gateway_order_id;
ALTER TABLE orders RENAME COLUMN razorpay_payment_id TO gateway_payment_id;
ALTER INDEX IF EXISTS idx_orders_razorpay_order RENAME TO idx_orders_gateway_order;

ALTER TABLE payment RENAME COLUMN razorpay_order_id TO gateway_order_id;
ALTER TABLE payment RENAME COLUMN razorpay_payment_id TO gateway_payment_id;
ALTER TABLE payment RENAME COLUMN razorpay_signature TO gateway_signature;
ALTER INDEX IF EXISTS idx_payment_razorpay_order RENAME TO idx_payment_gateway_order;
ALTER INDEX IF EXISTS idx_payment_razorpay_payment RENAME TO idx_payment_gateway_payment;

ALTER TABLE refund RENAME COLUMN razorpay_refund_id TO gateway_refund_id;
ALTER INDEX IF EXISTS idx_refund_razorpay RENAME TO idx_refund_gateway;

-- Settlements / rider payouts
ALTER TABLE settlement RENAME COLUMN razorpayx_payout_id TO cashfree_transfer_id;
ALTER TABLE rider_payouts RENAME COLUMN razorpay_payout_id TO cashfree_transfer_id;

-- Saved payment methods (drop/recreate checks that reference old column)
ALTER TABLE saved_payment_methods DROP CONSTRAINT IF EXISTS chk_saved_payment_methods_upi;
ALTER TABLE saved_payment_methods DROP CONSTRAINT IF EXISTS chk_saved_payment_methods_card;
ALTER TABLE saved_payment_methods RENAME COLUMN razorpay_token_id TO gateway_token_id;
ALTER TABLE saved_payment_methods ADD CONSTRAINT chk_saved_payment_methods_upi CHECK (
    type <> 'UPI'
    OR (upi_id IS NOT NULL AND upi_handle IS NOT NULL AND gateway_token_id IS NULL
        AND card_last4 IS NULL AND card_network IS NULL AND card_type IS NULL)
);
ALTER TABLE saved_payment_methods ADD CONSTRAINT chk_saved_payment_methods_card CHECK (
    type <> 'CARD'
    OR (gateway_token_id IS NOT NULL AND card_last4 IS NOT NULL
        AND card_network IS NOT NULL AND card_type IS NOT NULL
        AND upi_id IS NULL AND upi_handle IS NULL)
);
COMMENT ON COLUMN saved_payment_methods.gateway_token_id IS 'Encrypted gateway token (ex razorpay_token_id)';
