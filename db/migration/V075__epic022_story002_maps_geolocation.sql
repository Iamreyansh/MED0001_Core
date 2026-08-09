-- EPIC-022 / STORY-002: Maps & Geolocation API call log + geocode cache
-- Rollback:
--   DROP TABLE IF EXISTS maps_geocode_cache;
--   DROP TABLE IF EXISTS maps_api_call_log;
-- Notes: estimated_cost_rs in INR; request_summary is sanitized (no full PII addresses).

CREATE TABLE maps_api_call_log (
    id UUID PRIMARY KEY,
    api_type VARCHAR(20) NOT NULL,
    request_summary VARCHAR(200) NOT NULL,
    response_status VARCHAR(20) NOT NULL,
    latency_ms INTEGER NOT NULL,
    was_cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    estimated_cost_rs DECIMAL(6, 4) NOT NULL DEFAULT 0,
    called_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    calling_service VARCHAR(50) NULL,
    CONSTRAINT maps_api_call_log_api_type_chk CHECK (
        api_type IN (
            'GEOCODE',
            'REVERSE_GEOCODE',
            'DISTANCE_MATRIX',
            'DIRECTIONS',
            'ZONE_CHECK'
        )
    )
);

CREATE INDEX idx_maps_api_call_log_called_at
    ON maps_api_call_log (called_at);

CREATE INDEX idx_maps_api_call_log_api_type_called_at
    ON maps_api_call_log (api_type, called_at);

CREATE TABLE maps_geocode_cache (
    cache_key VARCHAR(500) PRIMARY KEY,
    lat DECIMAL(10, 7) NOT NULL,
    lng DECIMAL(10, 7) NOT NULL,
    formatted_address TEXT NOT NULL,
    place_id VARCHAR(100) NULL,
    cached_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_maps_geocode_cache_expires_at
    ON maps_geocode_cache (expires_at);
