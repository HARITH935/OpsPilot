package com.opspilot.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private static final UUID ORDER = UUID.randomUUID();

    @Test
    void ofFillsInIdTypeOrderAndTime() {
        Instant before = Instant.now();

        EventEnvelope envelope = EventEnvelope.of(new OrderConfirmed(ORDER), "trace-1");

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventType()).isEqualTo("OrderConfirmed");
        assertThat(envelope.aggregateId()).isEqualTo(ORDER);   // later also the Kafka key
        assertThat(envelope.occurredAt()).isBetween(before, Instant.now());
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
    }

    @Test
    void everyEventGetsItsOwnId() {
        assertThat(EventEnvelope.of(new OrderConfirmed(ORDER), null).eventId())
                .isNotEqualTo(EventEnvelope.of(new OrderConfirmed(ORDER), null).eventId());
    }

    @Test
    void envelopeCannotContradictItsPayload() {
        OrderConfirmed payload = new OrderConfirmed(ORDER);

        assertThatThrownBy(() -> new EventEnvelope(UUID.randomUUID(), "OrderCreated", ORDER, Instant.now(), null, 1, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> new EventEnvelope(
                UUID.randomUUID(), "OrderConfirmed", UUID.randomUUID(), Instant.now(), null, 1, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void versionStartsAtOne() {
        assertThatThrownBy(() -> new EventEnvelope(
                UUID.randomUUID(), "OrderConfirmed", ORDER, Instant.now(), null, 0, new OrderConfirmed(ORDER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}
