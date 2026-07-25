-- EPIC-001 / STORY-002: pharmacy staff authentication
-- Rollback: DROP TABLE IF EXISTS auth_login_audit;
--           DROP TABLE IF EXISTS pharmacy_staff_assignment;
--           DROP TABLE IF EXISTS pharmacy_staff;
--           DROP TABLE IF EXISTS pharmacy_roles;
--           DROP TABLE IF EXISTS pharmacies;
-- Notes: pharmacies/pharmacy_roles are stub tables owned by auth until EPIC-003/005 stories land.
--        pharmacy_id in sessions was already created in V002; no ALTER needed.

CREATE TABLE pharmacies (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    logo_url VARCHAR(1024),
    city VARCHAR(100),
    subscription_plan VARCHAR(32) NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE pharmacy_roles (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pharmacy_roles_code UNIQUE (code)
);

INSERT INTO pharmacy_roles (id, code, name) VALUES
    ('00000000-0000-0000-0001-000000000001', 'pharmacy_owner',  'Pharmacy Owner'),
    ('00000000-0000-0000-0001-000000000002', 'pharmacist',      'Pharmacist'),
    ('00000000-0000-0000-0001-000000000003', 'cashier',         'Cashier'),
    ('00000000-0000-0000-0001-000000000004', 'pharmacy_staff',  'Pharmacy Staff');

CREATE TABLE pharmacy_staff (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(15),
    password_hash VARCHAR(60) NOT NULL,
    pos_pin_hash VARCHAR(60),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts SMALLINT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    invited_by UUID REFERENCES pharmacy_staff(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_pharmacy_staff_email UNIQUE (email),
    CONSTRAINT uq_pharmacy_staff_phone UNIQUE (phone),
    CONSTRAINT chk_pharmacy_staff_identifier CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

CREATE INDEX idx_pharmacy_staff_email ON pharmacy_staff (email) WHERE email IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_pharmacy_staff_phone ON pharmacy_staff (phone) WHERE phone IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE pharmacy_staff_assignment (
    id UUID PRIMARY KEY,
    staff_id UUID NOT NULL REFERENCES pharmacy_staff(id),
    pharmacy_id UUID NOT NULL REFERENCES pharmacies(id),
    role_id UUID NOT NULL REFERENCES pharmacy_roles(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    removed_at TIMESTAMPTZ,
    CONSTRAINT uq_pharmacy_staff_assignment UNIQUE (staff_id, pharmacy_id)
);

CREATE INDEX idx_pharmacy_staff_assignment_staff ON pharmacy_staff_assignment (staff_id) WHERE is_active = TRUE;

CREATE TABLE auth_login_audit (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(32) NOT NULL DEFAULT 'pharmacy_staff',
    identifier VARCHAR(255),
    staff_id UUID,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(64),
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_login_audit_staff_id ON auth_login_audit (staff_id) WHERE staff_id IS NOT NULL;
CREATE INDEX idx_auth_login_audit_created_at ON auth_login_audit (created_at);
