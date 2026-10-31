-- inventory-service schema, version 1. Flyway runs each V<n>__*.sql file once, in order.
-- Never edit this file after it is merged: add the next V<n>__... instead.

CREATE TABLE products (
    sku      VARCHAR(64)    PRIMARY KEY,                             -- natural key: the code shop and orders use
    name     VARCHAR(200)   NOT NULL,
    price    NUMERIC(12, 2) NOT NULL CHECK (price > 0),               -- NUMERIC, not float: money must be exact
    stock    INTEGER        NOT NULL CHECK (stock >= 0),              -- units on hand in the warehouse
    reserved INTEGER        NOT NULL DEFAULT 0 CHECK (reserved >= 0), -- units held for orders not paid yet (1.6)
    version  BIGINT         NOT NULL DEFAULT 0,                       -- optimistic locking counter (@Version)
    -- Can't hold more units than are on hand, so "available = stock - reserved" is never negative
    CONSTRAINT products_reserved_within_stock CHECK (reserved <= stock)
);

-- Transactional outbox: events are written here in the same transaction as the stock change, then published to Kafka (Phase 2)
CREATE TABLE outbox (
    id           UUID          PRIMARY KEY,
    aggregate_id UUID          NOT NULL,    -- the order the event is about: keeps each order's events in sequence
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
