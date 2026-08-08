-- EPIC-010 / STORY-007: reorder_attempt_log (analytics)
-- Rollback: DROP TABLE IF EXISTS reorder_attempt_log;
-- Notes: optional analytics for reorder fill rates; no soft-delete (append-only log).

CREATE TABLE reorder_attempt_log (
    id                 UUID PRIMARY KEY,
    customer_id        UUID NOT NULL REFERENCES customers (id),
    source_order_id    UUID NOT NULL REFERENCES orders (id),
    resulting_cart_id  UUID NULL REFERENCES carts (id),
    pharmacy_changed   BOOLEAN NOT NULL DEFAULT FALSE,
    items_requested    INT NOT NULL,
    items_added        INT NOT NULL,
    items_excluded     INT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reorder_attempt_customer_created
    ON reorder_attempt_log (customer_id, created_at DESC);

CREATE INDEX idx_reorder_attempt_source_order
    ON reorder_attempt_log (source_order_id);
