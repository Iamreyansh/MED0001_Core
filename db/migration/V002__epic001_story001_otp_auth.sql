-- EPIC-001 / STORY-001: customer OTP auth
-- Rollback: DROP TABLE IF EXISTS sessions; DROP TABLE IF EXISTS otp_sessions; DROP TABLE IF EXISTS customers;
-- Notes: bcrypt hash only in otp_sessions; sessions refresh_token_hash is SHA-256 hex

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    phone VARCHAR(15) NOT NULL,
    device_tokens TEXT[],
    name VARCHAR(255),
    avatar_url VARCHAR(1024),
    date_of_birth DATE,
    gender VARCHAR(32),
    preferred_language VARCHAR(16),
    segment VARCHAR(64),
    wallet_balance_paise BIGINT NOT NULL DEFAULT 0,
    loyalty_points INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_customers_phone UNIQUE (phone)
);

CREATE TABLE otp_sessions (
    id UUID PRIMARY KEY,
    phone VARCHAR(15) NOT NULL,
    otp_hash VARCHAR(60) NOT NULL,
    attempts SMALLINT NOT NULL DEFAULT 0,
    device_info JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    locked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_otp_sessions_phone ON otp_sessions (phone);
CREATE INDEX idx_otp_sessions_created_at ON otp_sessions (created_at);

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    user_type VARCHAR(20) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    pharmacy_id UUID,
    token_scope VARCHAR(20) NOT NULL DEFAULT 'full',
    device_info JSONB,
    ip_address INET NOT NULL,
    user_agent TEXT,
    country CHAR(2),
    city VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_sessions_refresh_token_hash UNIQUE (refresh_token_hash)
);

CREATE INDEX idx_sessions_user_id ON sessions (user_id);
