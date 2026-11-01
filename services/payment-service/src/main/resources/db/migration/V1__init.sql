-- payment-service schema, version 1. Flyway runs each V<n>__*.sql file once, in order.
-- Never edit this file after it is merged: add the next V<n>__... instead.

CREATE TABLE payments (
    id             UUID           PRIMARY KEY,
    order_id       UUID           NOT NULL UNIQUE,   -- one payment per order: an order is never stored as paid twice
    amount         NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status         VARCHAR(16)    NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    failure_reason VARCHAR(64),
    version        BIGINT         NOT NULL,          -- @Version: lets Spring Data see a payment is new (see Payment)
    created_at     TIMESTAMPTZ    NOT NULL,
    -- A failure reason exactly when the payment failed
    CONSTRAINT payments_reason_only_when_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);
-- "List payments with status X, newest first" — the main read query
CREATE INDEX idx_payments_status_created_at ON payments (status, created_at);

-- Transactional outbox: events are written here in the same transaction as the payment, then published to Kafka (Phase 2)
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
