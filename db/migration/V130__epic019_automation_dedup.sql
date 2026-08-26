-- EPIC-019: durable automation fire dedup
-- Rollback: DROP TABLE IF EXISTS automation_dedup;
-- Notes: last fire per (rule_id, entity_id); window checked in JdbcDedupAdapter.

CREATE TABLE automation_dedup (
    rule_id         UUID NOT NULL,
    entity_id       UUID NOT NULL,
    last_fired_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (rule_id, entity_id)
);
