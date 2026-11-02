-- The outbox and processed_events tables exactly as every service's V1 Flyway migration creates them
-- (tests only: here there is no service)
CREATE TABLE outbox (
    id           UUID          PRIMARY KEY,
    aggregate_id UUID          NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    payload      JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE TABLE processed_events (
    event_id     UUID          PRIMARY KEY,
    processed_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
