-- Production readiness: critical FKs + support/payment integrity
-- Rollback:
--   ALTER TABLE payment DROP CONSTRAINT IF EXISTS fk_payment_order;
--   ALTER TABLE payment DROP CONSTRAINT IF EXISTS fk_payment_customer;
--   ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS fk_support_tickets_customer;
--   ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS fk_support_tickets_order;
--   ALTER TABLE inventory_reservation DROP CONSTRAINT IF EXISTS fk_inventory_reservation_order;
--   DROP TRIGGER IF EXISTS trg_support_ticket_messages_no_update ON support_ticket_messages;
--   DROP FUNCTION IF EXISTS forbid_support_ticket_message_mutation();
-- Notes: NOT VALID so existing orphans do not block migrate; validate after cleanup.

ALTER TABLE payment
    ADD CONSTRAINT fk_payment_order
    FOREIGN KEY (order_id) REFERENCES orders (id) NOT VALID;

ALTER TABLE payment
    ADD CONSTRAINT fk_payment_customer
    FOREIGN KEY (customer_id) REFERENCES customers (id) NOT VALID;

ALTER TABLE support_tickets
    ADD CONSTRAINT fk_support_tickets_customer
    FOREIGN KEY (customer_id) REFERENCES customers (id) NOT VALID;

ALTER TABLE support_tickets
    ADD CONSTRAINT fk_support_tickets_order
    FOREIGN KEY (order_id) REFERENCES orders (id) NOT VALID;

ALTER TABLE inventory_reservation
    ADD CONSTRAINT fk_inventory_reservation_order
    FOREIGN KEY (order_id) REFERENCES orders (id) NOT VALID;

CREATE OR REPLACE FUNCTION forbid_support_ticket_message_mutation()
RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'support_ticket_messages is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_support_ticket_messages_no_update ON support_ticket_messages;
CREATE TRIGGER trg_support_ticket_messages_no_update
    BEFORE UPDATE OR DELETE ON support_ticket_messages
    FOR EACH ROW
    EXECUTE FUNCTION forbid_support_ticket_message_mutation();
