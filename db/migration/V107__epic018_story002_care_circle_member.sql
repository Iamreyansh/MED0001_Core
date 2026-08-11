-- EPIC-018 / STORY-002: care_circle_member
-- Rollback:
--   DROP INDEX IF EXISTS uq_care_circle_member_self_active;
--   DROP INDEX IF EXISTS idx_care_circle_member_customer;
--   DROP TABLE IF EXISTS care_circle_member;
-- Notes: soft delete via deleted_at; one active SELF per customer via partial unique index.

CREATE TABLE care_circle_member (
    id              UUID PRIMARY KEY,
    customer_id     UUID NOT NULL REFERENCES customers (id),
    name            VARCHAR(100) NOT NULL,
    age             INTEGER NOT NULL,
    relationship    VARCHAR(20) NOT NULL,
    avatar_emoji    VARCHAR(10) NOT NULL DEFAULT '👤',
    avatar_color    VARCHAR(7) NOT NULL DEFAULT '#6B7280',
    is_self         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL,
    deleted_at      TIMESTAMPTZ NULL,
    CONSTRAINT chk_care_circle_member_age CHECK (age >= 0 AND age <= 120),
    CONSTRAINT chk_care_circle_member_relationship CHECK (relationship IN (
        'SELF', 'SPOUSE', 'CHILD', 'PARENT', 'SIBLING', 'OTHER'
    )),
    CONSTRAINT chk_care_circle_member_avatar_color CHECK (avatar_color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX idx_care_circle_member_customer
    ON care_circle_member (customer_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_care_circle_member_self_active
    ON care_circle_member (customer_id)
    WHERE is_self = TRUE AND deleted_at IS NULL;
