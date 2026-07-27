-- EPIC-003 / STORY-003: Auto KYC verification
-- Rollback: DROP TABLE IF EXISTS kyc_verifications;
--           DROP TABLE IF EXISTS auto_kyc_jobs;
--           DROP TABLE IF EXISTS pincode_zone_mapping;

CREATE TABLE auto_kyc_jobs (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    triggered_by    UUID,
    trigger_source  VARCHAR(16) NOT NULL,
    overall_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    auto_activated  BOOLEAN NOT NULL DEFAULT FALSE,
    triggered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_auto_kyc_jobs_pharmacy ON auto_kyc_jobs (pharmacy_id);
CREATE INDEX idx_auto_kyc_jobs_pharmacy_status ON auto_kyc_jobs (pharmacy_id, overall_status);

CREATE TABLE kyc_verifications (
    id                UUID PRIMARY KEY,
    pharmacy_id       UUID NOT NULL REFERENCES pharmacies (id),
    job_id            UUID NOT NULL REFERENCES auto_kyc_jobs (id),
    verification_type VARCHAR(32) NOT NULL,
    api_provider      VARCHAR(100) NOT NULL,
    request_payload   JSONB NOT NULL,
    response_payload  JSONB,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    details           JSONB,
    admin_flags       JSONB NOT NULL DEFAULT '[]'::jsonb,
    retry_count       SMALLINT NOT NULL DEFAULT 0,
    next_retry_at     TIMESTAMPTZ,
    verified_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kyc_verifications_job ON kyc_verifications (job_id);
CREATE INDEX idx_kyc_verifications_pharmacy ON kyc_verifications (pharmacy_id);
CREATE INDEX idx_kyc_verifications_retry ON kyc_verifications (next_retry_at)
    WHERE status = 'ERROR' AND next_retry_at IS NOT NULL;

-- ponytail: minimal pincode→zone seed until EPIC-009 zone service; default zone used when unmapped
CREATE TABLE pincode_zone_mapping (
    pincode   VARCHAR(6) PRIMARY KEY,
    zone_id   UUID NOT NULL
);

INSERT INTO pincode_zone_mapping (pincode, zone_id) VALUES
    ('560001', 'a0000001-0000-4000-8000-000000000001'),
    ('560002', 'a0000001-0000-4000-8000-000000000001'),
    ('400001', 'a0000002-0000-4000-8000-000000000002')
ON CONFLICT (pincode) DO NOTHING;
