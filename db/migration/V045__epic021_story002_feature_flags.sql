-- EPIC-021 / STORY-002: feature_flags
-- Rollback: DROP TABLE IF EXISTS feature_flags;
-- Notes: seed-only create (no POST API); unique (name, environment); Redis cache keyed per env.

CREATE TABLE feature_flags (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(100) NOT NULL,
    description        TEXT NOT NULL,
    environment        VARCHAR(20) NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage SMALLINT NOT NULL DEFAULT 0,
    notes              TEXT,
    updated_by         UUID REFERENCES admin_staff (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_feature_flags_name_env UNIQUE (name, environment),
    CONSTRAINT chk_feature_flags_environment CHECK (
        environment IN ('production', 'staging', 'development')
    ),
    CONSTRAINT chk_feature_flags_rollout CHECK (
        rollout_percentage BETWEEN 0 AND 100
    )
);

CREATE INDEX idx_feature_flags_environment ON feature_flags (environment);

-- Story example flags (production) + staging/development copies for ops.
INSERT INTO feature_flags (id, name, description, environment, enabled, rollout_percentage, notes, updated_at)
VALUES
    ('a1000001-0000-4000-8000-000000000001', 'new_checkout_flow',
     'Enables the redesigned 3-step checkout experience', 'production', TRUE, 50,
     'Gradual rollout to 50% - monitoring cart abandonment rate', NOW()),
    ('a1000001-0000-4000-8000-000000000002', 'cod_enabled',
     'Cash on delivery payment option at checkout', 'production', TRUE, 100, NULL, NOW()),
    ('a1000001-0000-4000-8000-000000000003', 'ai_rx_auto_fill',
     'AI auto-fills medicine items from uploaded prescription image', 'production', FALSE, 0,
     'Disabled pending compliance review', NOW()),
    ('a1000001-0000-4000-8000-000000000011', 'new_checkout_flow',
     'Enables the redesigned 3-step checkout experience', 'staging', TRUE, 100, NULL, NOW()),
    ('a1000001-0000-4000-8000-000000000012', 'cod_enabled',
     'Cash on delivery payment option at checkout', 'staging', TRUE, 100, NULL, NOW()),
    ('a1000001-0000-4000-8000-000000000013', 'ai_rx_auto_fill',
     'AI auto-fills medicine items from uploaded prescription image', 'staging', TRUE, 25, NULL, NOW()),
    ('a1000001-0000-4000-8000-000000000021', 'new_checkout_flow',
     'Enables the redesigned 3-step checkout experience', 'development', TRUE, 100, NULL, NOW()),
    ('a1000001-0000-4000-8000-000000000022', 'cod_enabled',
     'Cash on delivery payment option at checkout', 'development', TRUE, 100, NULL, NOW()),
    ('a1000001-0000-4000-8000-000000000023', 'ai_rx_auto_fill',
     'AI auto-fills medicine items from uploaded prescription image', 'development', TRUE, 100, NULL, NOW());
