-- Stock held for an order while its payment runs (step 1.6).
-- Each row moves exactly once: HELD → COMMITTED (the order was paid) or HELD → RELEASED (it was not).
CREATE TABLE reservations (
    id         BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   UUID          NOT NULL,
    sku        VARCHAR(64)   NOT NULL REFERENCES products (sku),   -- can only hold products that exist
    qty        INTEGER       NOT NULL CHECK (qty > 0),
    status     VARCHAR(16)   NOT NULL CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED')),
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    -- One row per product per order, so a duplicate "reserve" can never hold the same stock twice.
    -- Its index starts with order_id, so it also serves "find this order's reservations".
    CONSTRAINT reservations_one_per_order_and_sku UNIQUE (order_id, sku)
);
