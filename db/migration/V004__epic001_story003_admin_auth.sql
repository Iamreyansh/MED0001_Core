-- EPIC-001 / STORY-003: admin staff authentication & MFA
-- Rollback: DROP TABLE IF EXISTS admin_auth_events;
--           DROP TABLE IF EXISTS admin_staff;
-- Notes: totp_secret stored as AES-256-GCM ciphertext (TEXT; plaintext base32 is ≤32 chars).
--        last_failed_at supports the 15-minute failure window (rule 6).
--        admin_staff is owned by auth until EPIC-021 staff invitation lands.

CREATE TABLE admin_staff (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    role VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    totp_secret TEXT,
    backup_codes JSONB,
    failed_login_attempts SMALLINT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    last_active_at TIMESTAMPTZ,
    invited_by UUID REFERENCES admin_staff(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_admin_staff_email UNIQUE (email),
    CONSTRAINT chk_admin_staff_role CHECK (role IN (
        'admin_super', 'admin_operations', 'admin_finance', 'admin_support', 'admin_compliance'
    )),
    CONSTRAINT chk_admin_staff_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INVITED'))
);

CREATE INDEX idx_admin_staff_email ON admin_staff (email) WHERE deleted_at IS NULL;

CREATE TABLE admin_auth_events (
    id UUID PRIMARY KEY,
    admin_id UUID REFERENCES admin_staff(id),
    event_type VARCHAR(40) NOT NULL,
    ip_address INET NOT NULL,
    user_agent TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_admin_auth_events_type CHECK (event_type IN (
        'LOGIN_SUCCESS', 'LOGIN_FAILED', 'MFA_SUCCESS', 'MFA_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT'
    ))
);

CREATE INDEX idx_admin_auth_events_admin_id ON admin_auth_events (admin_id) WHERE admin_id IS NOT NULL;
CREATE INDEX idx_admin_auth_events_created_at ON admin_auth_events (created_at);
