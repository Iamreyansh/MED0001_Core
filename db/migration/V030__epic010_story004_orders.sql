-- EPIC-010 / STORY-004: orders + daily IST order_number sequence
-- Rollback: DROP TABLE IF EXISTS orders;
--           DROP TABLE IF EXISTS order_number_sequence;
-- Notes: money BIGINT paise; items JSONB snapshot; status enum forward-compat with STORY-005.

CREATE TABLE order_number_sequence (
    date_ist DATE PRIMARY KEY,
    last_seq INT NOT NULL DEFAULT 0
);

CREATE TABLE orders (
    id                      UUID PRIMARY KEY,
    order_number            VARCHAR(32) NOT NULL,
    customer_id             UUID NOT NULL REFERENCES customers (id),
    pharmacy_id             UUID NOT NULL REFERENCES pharmacies (id),
    cart_id                 UUID NOT NULL REFERENCES carts (id),
    items                   JSONB NOT NULL,
    item_total_paise        BIGINT NOT NULL,
    coupon_code             VARCHAR(20) NULL,
    coupon_discount_paise   BIGINT NOT NULL DEFAULT 0,
    delivery_fee_paise      BIGINT NOT NULL,
    handling_fee_paise      BIGINT NOT NULL DEFAULT 500,
    wallet_applied_paise    BIGINT NOT NULL DEFAULT 0,
    total_payable_paise     BIGINT NOT NULL,
    payment_method          VARCHAR(20) NOT NULL,
    payment_status          VARCHAR(30) NOT NULL,
    razorpay_order_id       VARCHAR(64) NULL,
    razorpay_payment_id     VARCHAR(64) NULL,
    prescription_id         UUID NULL,
    delivery_address_id     UUID NOT NULL REFERENCES customer_addresses (id),
    delivery_instructions   VARCHAR(200) NULL,
    status                  VARCHAR(30) NOT NULL,
    rider_id                UUID NULL,
    delivery_otp            VARCHAR(4) NULL,
    placement_idempotency_key VARCHAR(128) NULL,
    confirmed_at            TIMESTAMPTZ NULL,
    estimated_delivery_at   TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMPTZ NULL,
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT uq_orders_placement_idempotency UNIQUE (placement_idempotency_key),
    CONSTRAINT chk_orders_payment_method
        CHECK (payment_method IN ('UPI', 'CARD', 'COD', 'WALLET')),
    CONSTRAINT chk_orders_payment_status
        CHECK (payment_status IN (
            'PENDING_COLLECTION',
            'AWAITING_PAYMENT',
            'PAID',
            'COLLECTED',
            'REFUNDED',
            'PARTIALLY_REFUNDED'
        )),
    CONSTRAINT chk_orders_status
        CHECK (status IN (
            'PAYMENT_PENDING',
            'PENDING_ACCEPTANCE',
            'ACCEPTED',
            'PACKING',
            'READY_FOR_PICKUP',
            'OUT_FOR_DELIVERY',
            'DELIVERED',
            'CANCELLED'
        ))
);

CREATE INDEX idx_orders_customer_created
    ON orders (customer_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_orders_pharmacy_status
    ON orders (pharmacy_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_orders_razorpay_order
    ON orders (razorpay_order_id)
    WHERE razorpay_order_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_orders_address_active
    ON orders (delivery_address_id)
    WHERE deleted_at IS NULL
      AND status NOT IN ('DELIVERED', 'CANCELLED');
