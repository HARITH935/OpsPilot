-- order-service schema, version 1. Flyway runs each V<n>__*.sql file once, in order.
-- Never edit this file after it is merged: add V2__..., V3__... instead.

CREATE TABLE orders (
    id              UUID           PRIMARY KEY,
    customer_id     VARCHAR(64)    NOT NULL,
    status          VARCHAR(16)    NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    total_amount    NUMERIC(12, 2),             -- NULL until the order is priced (decided in step 1.4)
    cancel_reason   VARCHAR(32),
    idempotency_key VARCHAR(100)   UNIQUE,      -- from the Idempotency-Key header: a retried POST must not create a 2nd order
    version         BIGINT         NOT NULL,    -- optimistic locking counter (@Version)
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL
);
-- "List orders with status X, newest first" — the main read query
CREATE INDEX idx_orders_status_created_at ON orders (status, created_at);

CREATE TABLE order_items (
    id         BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku        VARCHAR(64)    NOT NULL,
    qty        INTEGER        NOT NULL CHECK (qty > 0),
    unit_price NUMERIC(12, 2)                   -- NULL until priced, like total_amount
);
-- Postgres does not index foreign keys automatically; loading an order's items needs this
CREATE INDEX idx_order_items_order_id ON order_items (order_id);

-- Transactional outbox: events are written here in the same transaction as the order, then published to Kafka (Phase 2)
CREATE TABLE outbox (
    id           UUID          PRIMARY KEY,
    aggregate_id UUID          NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    payload      JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
-- Partial index: covers only unpublished rows, in the order the publisher reads them
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- Idempotent consumers: remember which Kafka events were already handled (Phase 2)
CREATE TABLE processed_events (
    event_id     UUID          PRIMARY KEY,
    processed_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
