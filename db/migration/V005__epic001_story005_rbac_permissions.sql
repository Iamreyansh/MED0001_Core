-- EPIC-001 / STORY-005: RBAC roles & permissions
-- Rollback: DROP TABLE IF EXISTS permissions;
--           ALTER TABLE pharmacy_roles DROP COLUMN IF EXISTS pharmacy_id, DROP COLUMN IF EXISTS display_name,
--             DROP COLUMN IF EXISTS is_system, DROP COLUMN IF EXISTS permissions, DROP COLUMN IF EXISTS created_by,
--             DROP COLUMN IF EXISTS updated_at, DROP COLUMN IF EXISTS deleted_at;
--           -- restore prior codes/rows as needed from V003
-- Notes: Evolves stub pharmacy_roles into system + per-pharmacy custom roles with permission arrays.
--        Admin role matrices remain code-defined (*:* for admin_super); permissions table is the catalog.

CREATE TABLE permissions (
    resource    VARCHAR(50)  NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    description TEXT         NOT NULL,
    domain      VARCHAR(20)  NOT NULL,
    CONSTRAINT pk_permissions PRIMARY KEY (resource, action, domain),
    CONSTRAINT chk_permissions_domain CHECK (domain IN ('admin', 'pharmacy'))
);

INSERT INTO permissions (resource, action, description, domain) VALUES
    ('orders', 'read', 'View order details and history', 'admin'),
    ('orders', 'write', 'Create and modify orders', 'admin'),
    ('orders', 'cancel', 'Cancel any order', 'admin'),
    ('pharmacies', 'read', 'View pharmacy profiles and details', 'admin'),
    ('pharmacies', 'update', 'Update pharmacy information', 'admin'),
    ('pharmacies', 'suspend', 'Suspend or reactivate a pharmacy', 'admin'),
    ('riders', 'read', 'View rider profiles and status', 'admin'),
    ('riders', 'assign', 'Manually assign riders to orders', 'admin'),
    ('finance', 'read', 'View financial reports and summaries', 'admin'),
    ('finance', 'release-payout', 'Trigger pharmacy payout releases', 'admin'),
    ('refunds', 'approve', 'Approve refund requests', 'admin'),
    ('customers', 'read', 'View customer profiles and orders', 'admin'),
    ('customers', 'notify', 'Send notifications to customers', 'admin'),
    ('tickets', 'read', 'View support tickets', 'admin'),
    ('tickets', 'write', 'Create, update, and close tickets', 'admin'),
    ('prescriptions', 'review', 'Review and approve/reject prescriptions', 'admin'),
    ('compliance', 'audit', 'Run compliance audits on pharmacies', 'admin'),
    ('catalogue', 'read', 'Browse the medicine catalogue', 'admin'),
    ('catalogue', 'update', 'Update medicine information and categories', 'admin'),
    ('analytics', 'finance', 'Access finance-specific analytics dashboards', 'admin'),
    ('settlements', 'read', 'View settlement records', 'admin'),
    ('settlements', 'process', 'Process pending settlements', 'admin'),
    ('taxes', 'read', 'View tax reports and filings', 'admin'),
    ('logistics', 'read', 'View delivery and logistics data', 'admin'),
    ('orders', 'read', 'View pharmacy orders', 'pharmacy'),
    ('orders', 'fulfill', 'Fulfill pharmacy orders', 'pharmacy'),
    ('orders', 'pos-create', 'Create counter/POS sales', 'pharmacy'),
    ('orders', 'dispatch', 'Dispatch orders for delivery', 'pharmacy'),
    ('inventory', 'read', 'View inventory stock', 'pharmacy'),
    ('inventory', 'write', 'Adjust inventory stock', 'pharmacy'),
    ('staff', 'read', 'View pharmacy staff', 'pharmacy'),
    ('staff', 'manage', 'Manage pharmacy staff and custom roles', 'pharmacy'),
    ('reports', 'read', 'View pharmacy reports', 'pharmacy'),
    ('prescriptions', 'verify', 'Verify prescriptions at dispense', 'pharmacy'),
    ('payments', 'collect', 'Collect POS/customer payments', 'pharmacy');

-- Expand pharmacy_roles for custom roles + permission arrays
ALTER TABLE pharmacy_roles
    ADD COLUMN pharmacy_id UUID REFERENCES pharmacies (id),
    ADD COLUMN display_name VARCHAR(100),
    ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN permissions TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN created_by UUID REFERENCES pharmacy_staff (id),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN deleted_at TIMESTAMPTZ;

UPDATE pharmacy_roles
SET display_name = name,
    is_system = TRUE,
    updated_at = NOW()
WHERE pharmacy_id IS NULL;

-- Align system role codes with STORY-005 (owner/manager/pharmacist/cashier/delivery)
UPDATE pharmacy_roles
SET code = 'owner',
    name = 'owner',
    display_name = 'Pharmacy Owner',
    permissions = ARRAY['*']::TEXT[]
WHERE id = '00000000-0000-0000-0001-000000000001';

UPDATE pharmacy_roles
SET code = 'pharmacist',
    name = 'pharmacist',
    display_name = 'Pharmacist',
    permissions = ARRAY['orders:fulfill', 'inventory:read', 'prescriptions:verify']::TEXT[]
WHERE id = '00000000-0000-0000-0001-000000000002';

UPDATE pharmacy_roles
SET code = 'cashier',
    name = 'cashier',
    display_name = 'Cashier',
    permissions = ARRAY['orders:read', 'orders:pos-create', 'payments:collect']::TEXT[]
WHERE id = '00000000-0000-0000-0001-000000000003';

UPDATE pharmacy_roles
SET code = 'manager',
    name = 'manager',
    display_name = 'Manager',
    permissions = ARRAY['orders:*', 'inventory:*', 'staff:read', 'reports:read']::TEXT[]
WHERE id = '00000000-0000-0000-0001-000000000004';

INSERT INTO pharmacy_roles (id, code, name, display_name, is_system, permissions, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0001-000000000005',
    'delivery',
    'delivery',
    'Delivery Staff',
    TRUE,
    ARRAY['orders:read', 'orders:dispatch']::TEXT[],
    NOW(),
    NOW()
);

ALTER TABLE pharmacy_roles
    ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE pharmacy_roles
    DROP CONSTRAINT uq_pharmacy_roles_code;

CREATE UNIQUE INDEX uq_pharmacy_roles_system_code
    ON pharmacy_roles (code)
    WHERE pharmacy_id IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_pharmacy_roles_custom_name
    ON pharmacy_roles (pharmacy_id, code)
    WHERE pharmacy_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_pharmacy_roles_pharmacy
    ON pharmacy_roles (pharmacy_id)
    WHERE deleted_at IS NULL;
