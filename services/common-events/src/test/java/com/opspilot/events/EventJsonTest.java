package com.opspilot.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The JSON round-trip tests: what one service writes, another must read back exactly
class EventJsonTest {

    private static final UUID ORDER = UUID.fromString("7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a");
    private static final UUID PAYMENT = UUID.fromString("2b9d6c1e-3a4f-4e5d-9c8b-7a6f5e4d3c2b");
    private static final UUID EVENT = UUID.fromString("0e1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b");

    // One example of every event in the catalog
    static Stream<EventPayload> everyEvent() {
        return Stream.of(
                new OrderCreated(ORDER, "c-1", List.of(new OrderCreated.Item("SKU-1", 2), new OrderCreated.Item("SKU-2", 1))),
                new OrderConfirmed(ORDER),
                new OrderCancelled(ORDER, "PAYMENT_FAILED"),
                new RefundRequested(ORDER, PAYMENT),
                new StockReserved(ORDER, List.of(new StockReserved.Item("SKU-1", 2, new BigDecimal("89.99"))),
                        new BigDecimal("179.98")),
                new StockRejected(ORDER, "SKU-10", "OUT_OF_STOCK"),
                new StockReleased(ORDER),
                new PaymentCompleted(ORDER, PAYMENT, new BigDecimal("179.98")),
                new PaymentFailed(ORDER, PAYMENT, new BigDecimal("179.98"), "PROVIDER_ERROR"),
                new PaymentRefunded(ORDER, PAYMENT, new BigDecimal("179.98")));
    }

    @ParameterizedTest
    @MethodSource("everyEvent")
    void everyEventSurvivesTheRoundTripToJsonAndBack(EventPayload payload) {
        EventEnvelope original = EventEnvelope.of(payload, "4bf92f3577b34da6a3ce929d0e0e4736");

        EventEnvelope copy = EventJson.read(EventJson.write(original));

        assertThat(copy).isEqualTo(original);   // records compare every field, the payload's included
        assertThat(copy.payload()).isInstanceOf(payload.getClass());
    }

    @Test
    void jsonHasTheShapeAgreedInTheArchitectureDoc() {
        EventEnvelope envelope = new EventEnvelope(EVENT, "StockReserved", ORDER, Instant.parse("2026-11-01T10:00:00Z"),
                "4bf92f3577b34da6a3ce929d0e0e4736", 1,
                new StockReserved(ORDER, List.of(new StockReserved.Item("SKU-1", 2, new BigDecimal("89.99"))),
                        new BigDecimal("179.98")));

        // Exactly what goes over the wire: times as text, prices as exact numbers, the payload's own fields
        String expected = """
                {
                  "eventId": "0e1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b",
                  "eventType": "StockReserved",
                  "aggregateId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a",
                  "occurredAt": "2026-11-01T10:00:00Z",
                  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                  "version": 1,
                  "payload": {
                    "orderId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a",
                    "items": [ { "sku": "SKU-1", "qty": 2, "unitPrice": 89.99 } ],
                    "totalAmount": 179.98
                  }
                }
                """;
        assertThat(tree(EventJson.write(envelope))).isEqualTo(tree(expected));   // same fields and values, any order
    }

    @Test
    void moneyKeepsItsExactDigitsAndScale() {
        // 12.50 must stay 12.50 (not 12.5), and long amounts must not lose digits on the way
        BigDecimal price = new BigDecimal("12.50");
        BigDecimal total = new BigDecimal("1234567890.10");
        EventEnvelope original = EventEnvelope.of(
                new StockReserved(ORDER, List.of(new StockReserved.Item("SKU-1", 1, price)), total), null);

        String json = EventJson.write(original);
        StockReserved read = (StockReserved) EventJson.read(json).payload();

        assertThat(json).contains("\"unitPrice\":12.50", "\"totalAmount\":1234567890.10");
        assertThat(read.items().get(0).unitPrice()).isEqualTo(price);   // equals() also compares the scale
        assertThat(read.totalAmount()).isEqualTo(total);
        assertThat(EventJson.write(EventJson.read(json))).isEqualTo(json);   // read + write gives the same text back
    }

    @Test
    void readsJsonWrittenByHand() {
        // What another service would put on Kafka: no Java object was involved in writing this
        String json = """
                {"eventId": "0e1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b", "eventType": "PaymentFailed",
                 "aggregateId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a", "occurredAt": "2026-11-01T10:00:05Z",
                 "traceId": null, "version": 1,
                 "payload": {"orderId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a",
                             "paymentId": "2b9d6c1e-3a4f-4e5d-9c8b-7a6f5e4d3c2b",
                             "amount": 24.99, "reason": "PROVIDER_ERROR"}}
                """;

        EventEnvelope envelope = EventJson.read(json);

        assertThat(envelope.payload())
                .isEqualTo(new PaymentFailed(ORDER, PAYMENT, new BigDecimal("24.99"), "PROVIDER_ERROR"));
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-11-01T10:00:05Z"));
        assertThat(envelope.traceId()).isNull();
    }

    @Test
    void fieldsItDoesNotKnowAreIgnored() {
        // A newer service added "priority" to the envelope and "note" to the payload; this reader must still cope
        String json = """
                {"eventId": "0e1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b", "eventType": "OrderConfirmed",
                 "aggregateId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a", "occurredAt": "2026-11-01T10:00:09Z",
                 "traceId": null, "version": 1, "priority": "high",
                 "payload": {"orderId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a", "note": "gift wrap"}}
                """;

        assertThat(EventJson.read(json).payload()).isEqualTo(new OrderConfirmed(ORDER));
    }

    @Test
    void unknownEventTypeIsRefused() {
        String json = """
                {"eventId": "0e1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b", "eventType": "OrderShipped",
                 "aggregateId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a", "occurredAt": "2026-11-01T10:00:09Z",
                 "traceId": null, "version": 1, "payload": {"orderId": "7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a"}}
                """;

        assertThatThrownBy(() -> EventJson.read(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OrderShipped");
    }

    private static JsonNode tree(String json) {
        return JsonMapper.shared().readTree(json);
    }
}
