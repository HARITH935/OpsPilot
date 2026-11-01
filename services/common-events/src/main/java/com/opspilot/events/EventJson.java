package com.opspilot.events;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns envelopes into JSON and back. The format is part of the contract, so it is fixed here, in one place,
 * instead of depending on how each service happens to configure its own JSON mapper.
 */
public final class EventJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)   // times as text, "2026-11-01T10:00:00Z", not numbers
            // Ignore fields this code doesn't know: a newer service may add one without breaking older readers
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();   // a JsonMapper is thread-safe once built, so one shared instance is enough

    private EventJson() {
        // static methods only
    }

    public static String write(EventEnvelope envelope) {
        return MAPPER.writeValueAsString(envelope);
    }

    // Two steps: read the envelope with the payload still as raw JSON, then let eventType decide which record it is
    public static EventEnvelope read(String json) {
        RawEnvelope raw = MAPPER.readValue(json, RawEnvelope.class);
        EventType type = EventType.fromWireName(raw.eventType());   // unknown names are refused here
        EventPayload payload = MAPPER.treeToValue(raw.payload(), type.payloadType());
        return new EventEnvelope(raw.eventId(), raw.eventType(), raw.aggregateId(), raw.occurredAt(),
                raw.traceId(), raw.version(), payload);
    }

    // The envelope as it arrives: the same fields, but the payload not yet turned into a record
    record RawEnvelope(UUID eventId, String eventType, UUID aggregateId, Instant occurredAt, String traceId,
                       int version, JsonNode payload) {
    }
}
