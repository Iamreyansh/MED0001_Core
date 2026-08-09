-- EPIC-014 / STORY-001: SaaS plan catalogue, add-ons, module matrix, crm_account stub
-- Rollback:
--   DROP TABLE IF EXISTS crm_account_addon;
--   DROP TABLE IF EXISTS saas_module_matrix;
--   DROP TABLE IF EXISTS saas_addon;
--   DROP TABLE IF EXISTS saas_plan;
--   DROP TABLE IF EXISTS crm_account;
-- Notes: money as BIGINT paise. crm_account.current_plan_name stubs ACTIVE subscribers until STORY-002.
--        Legacy pharmacies.plan GROWTH→RETAIL_PRO, PRO→ENTERPRISE on backfill.

CREATE TABLE IF NOT EXISTS crm_account (
    id UUID PRIMARY KEY,
    pharmacy_id UUID NOT NULL UNIQUE,
    current_plan_name VARCHAR(32) NOT NULL DEFAULT 'FREE',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_crm_account_plan_status
    ON crm_account (current_plan_name, status)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS saas_plan (
    id UUID PRIMARY KEY,
    name VARCHAR(32) NOT NULL UNIQUE,
    price_monthly_paise BIGINT NOT NULL DEFAULT 0,
    seat_limit INT,
    invoice_cap_monthly INT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_custom_pricing BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS saas_addon (
    id UUID PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    price_monthly_paise BIGINT NOT NULL DEFAULT 0,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS crm_account_addon (
    account_id UUID NOT NULL REFERENCES crm_account (id),
    addon_id UUID NOT NULL REFERENCES saas_addon (id),
    effective_from TIMESTAMPTZ NOT NULL,
    detached_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, addon_id)
);

CREATE INDEX IF NOT EXISTS idx_crm_account_addon_active
    ON crm_account_addon (addon_id)
    WHERE detached_at IS NULL;

CREATE TABLE IF NOT EXISTS saas_module_matrix (
    id UUID PRIMARY KEY,
    module_id VARCHAR(50) NOT NULL UNIQUE,
    module_name VARCHAR(100) NOT NULL,
    module_code VARCHAR(50) NOT NULL,
    group_name VARCHAR(50) NOT NULL,
    plan_names TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO saas_plan (
    id, name, price_monthly_paise, seat_limit, invoice_cap_monthly,
    is_active, is_custom_pricing, created_at, updated_at
) VALUES
    ('a1000000-0000-4000-8000-000000000001', 'FREE', 0, 1, 100, TRUE, FALSE, NOW(), NOW()),
    ('a1000000-0000-4000-8000-000000000002', 'STARTER', 69900, 2, 500, TRUE, FALSE, NOW(), NOW()),
    ('a1000000-0000-4000-8000-000000000003', 'RETAIL_PRO', 149900, 5, NULL, TRUE, FALSE, NOW(), NOW()),
    ('a1000000-0000-4000-8000-000000000004', 'ENTERPRISE', 0, NULL, NULL, TRUE, TRUE, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

INSERT INTO saas_addon (
    id, name, price_monthly_paise, description, is_active, created_at, updated_at
) VALUES
    ('a2000000-0000-4000-8000-000000000001', 'E_INVOICE', 19900, 'GST e-invoice generation', TRUE, NOW(), NOW()),
    ('a2000000-0000-4000-8000-000000000002', 'WHATSAPP_INTEGRATION', 29900, 'WhatsApp prescription/order alerts', TRUE, NOW(), NOW()),
    ('a2000000-0000-4000-8000-000000000003', 'EXTRA_SEAT', 14900, 'Additional staff user', TRUE, NOW(), NOW()),
    ('a2000000-0000-4000-8000-000000000004', 'API_ACCESS', 49900, 'REST API for integrations', TRUE, NOW(), NOW()),
    ('a2000000-0000-4000-8000-000000000005', 'BRANCH', 39900, 'Multi-branch support', TRUE, NOW(), NOW()),
    ('a2000000-0000-4000-8000-000000000006', 'ANALYTICS', 24900, 'Detailed ERP reporting', TRUE, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

INSERT INTO saas_module_matrix (
    id, module_id, module_name, module_code, group_name, plan_names, created_at, updated_at
) VALUES
    ('a3000000-0000-4000-8000-000000000001', 'mod_inventory', 'Inventory Management', 'INVENTORY', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000002', 'mod_billing', 'Billing / POS', 'BILLING', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000003', 'mod_purchase_orders', 'Purchase Orders', 'PURCHASE_ORDERS', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000004', 'mod_customer_ledger', 'Customer Ledger', 'CUSTOMER_LEDGER', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000005', 'mod_reports_basic', 'Basic Reports', 'REPORTS_BASIC', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000006', 'mod_prescription', 'Prescription Management', 'PRESCRIPTION_MANAGEMENT', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000007', 'mod_expiry_alerts', 'Expiry Alerts', 'EXPIRY_ALERTS', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000008', 'mod_staff', 'Staff Management', 'STAFF_MANAGEMENT', 'CORE',
     ARRAY['FREE','STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000009', 'mod_khata', 'Khata / Credit Sales', 'KHATA', 'ADVANCED',
     ARRAY['STARTER','RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000010', 'mod_offers', 'Offers & Promotions', 'OFFERS', 'ADVANCED',
     ARRAY['RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000011', 'mod_distributors', 'Distributor Management', 'DISTRIBUTORS', 'ADVANCED',
     ARRAY['RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000012', 'mod_reorder', 'Smart Reorder', 'REORDER', 'ADVANCED',
     ARRAY['RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000013', 'mod_inventory_online', 'Online Inventory Visibility', 'INVENTORY_ONLINE', 'ADVANCED',
     ARRAY['RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000014', 'mod_analytics_adv', 'Advanced Analytics', 'ADVANCED_ANALYTICS', 'ANALYTICS',
     ARRAY['RETAIL_PRO','ENTERPRISE'], NOW(), NOW()),
    ('a3000000-0000-4000-8000-000000000015', 'mod_api', 'API Access', 'API_ACCESS', 'ADVANCED',
     ARRAY['ENTERPRISE'], NOW(), NOW())
ON CONFLICT (module_id) DO NOTHING;

-- Backfill crm_account 1:1 with pharmacies; map legacy plan names.
INSERT INTO crm_account (id, pharmacy_id, current_plan_name, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    p.id,
    CASE UPPER(COALESCE(NULLIF(p.plan, ''), NULLIF(p.subscription_plan, ''), 'FREE'))
        WHEN 'GROWTH' THEN 'RETAIL_PRO'
        WHEN 'PRO' THEN 'ENTERPRISE'
        WHEN 'STARTER' THEN 'STARTER'
        WHEN 'RETAIL_PRO' THEN 'RETAIL_PRO'
        WHEN 'ENTERPRISE' THEN 'ENTERPRISE'
        ELSE 'FREE'
    END,
    'ACTIVE',
    NOW(),
    NOW()
FROM pharmacies p
WHERE p.deleted_at IS NULL
ON CONFLICT (pharmacy_id) DO NOTHING;
