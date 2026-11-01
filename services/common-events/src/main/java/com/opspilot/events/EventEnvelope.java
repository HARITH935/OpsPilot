package com.opspilot.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The wrapper every event travels in (architecture doc B2.1): the same seven fields for every kind of event, so any
 * service can read the outside before it knows what is inside.
 */
public record EventEnvelope(
        UUID eventId,          // unique per event: lets consumers spot a duplicate delivery (processed_events, Phase 2)
        String eventType,      // which record the payload is, e.g. "StockReserved" (see EventType)
        UUID aggregateId,      // the order the event is about; also the Kafka key, so one order's events stay in sequence
        Instant occurredAt,
        String traceId,        // OpenTelemetry trace id, to follow one order across services (Phase 6); may be null
        int version,           // version of the payload's format, so it can change later without confusing consumers
        EventPayload payload
) {

    public static final int CURRENT_VERSION = 1;

    // An envelope can't contradict its payload: the type name and the order id must match it
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        String expectedType = EventType.of(payload).wireName();
        if (!expectedType.equals(eventType)) {
            throw new IllegalArgumentException("eventType '" + eventType + "' does not match the payload: " + expectedType);
        }
        if (!payload.orderId().equals(aggregateId)) {
            throw new IllegalArgumentException(
                    "aggregateId " + aggregateId + " is not the payload's order " + payload.orderId());
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be 1 or higher, was " + version);
        }
    }

    // A brand-new event: fresh id, the right type name, the payload's order, now, current format version
    public static EventEnvelope of(EventPayload payload, String traceId) {
        return new EventEnvelope(UUID.randomUUID(), EventType.of(payload).wireName(), payload.orderId(),
                Instant.now(), traceId, CURRENT_VERSION, payload);
    }
}
