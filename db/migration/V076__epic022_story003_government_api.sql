-- EPIC-022 / STORY-003: Government API verification cache + call log
-- Rollback:
--   DROP TABLE IF EXISTS government_api_call_log;
--   DROP TABLE IF EXISTS government_verification_cache;
-- Notes: cache TTL 7 days (expires_at); identifiers only in logs (no credentials/Aadhaar).
--   state stored as '' when N/A so (verification_type, identifier, state) is uniquely keyed.

CREATE TABLE government_verification_cache (
    id UUID PRIMARY KEY,
    verification_type VARCHAR(20) NOT NULL,
    identifier VARCHAR(100) NOT NULL,
    state VARCHAR(50) NOT NULL DEFAULT '',
    result_json JSONB NOT NULL,
    is_valid BOOLEAN NOT NULL,
    expiry_date DATE NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT government_verification_cache_type_chk CHECK (
        verification_type IN ('GSTIN', 'DRUG_LICENCE', 'FSSAI', 'DIGILOCKER')
    ),
    CONSTRAINT uq_government_verification_cache_lookup
        UNIQUE (verification_type, identifier, state)
);

CREATE INDEX idx_government_verification_cache_expires_at
    ON government_verification_cache (expires_at);

CREATE TABLE government_api_call_log (
    id UUID PRIMARY KEY,
    api_type VARCHAR(20) NOT NULL,
    identifier VARCHAR(100) NOT NULL,
    http_status SMALLINT NULL,
    result_status VARCHAR(20) NOT NULL,
    latency_ms INTEGER NOT NULL,
    was_cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    entity_type VARCHAR(20) NULL,
    entity_id UUID NULL,
    called_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT government_api_call_log_api_type_chk CHECK (
        api_type IN ('GSTN', 'DRUG_REGISTRY', 'FSSAI', 'DIGILOCKER')
    ),
    CONSTRAINT government_api_call_log_result_status_chk CHECK (
        result_status IN ('OK', 'NOT_FOUND', 'RATE_LIMITED', 'ERROR', 'PENDING', 'INVALID')
    )
);

CREATE INDEX idx_government_api_call_log_called_at
    ON government_api_call_log (called_at);

CREATE INDEX idx_government_api_call_log_api_type_called_at
    ON government_api_call_log (api_type, called_at);
