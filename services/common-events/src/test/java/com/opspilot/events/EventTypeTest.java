package com.opspilot.events;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class EventTypeTest {

    @Test
    void everyEventRecordIsInTheCatalogExactlyOnce() {
        // getPermittedSubclasses(): the records listed after "permits" in the sealed EventPayload
        Class<?>[] records = EventPayload.class.getPermittedSubclasses();

        for (Class<?> record : records) {
            assertThat(Arrays.stream(EventType.values()).filter(type -> type.payloadType() == record))
                    .as("catalog entries for %s", record.getSimpleName())
                    .hasSize(1);
        }
        assertThat(EventType.values()).hasSameSizeAs(records);
    }

    @Test
    void eventsGoToTheTopicsFromTheArchitectureDoc() {
        Map<String, List<String>> eventsByTopic = Arrays.stream(EventType.values())
                .collect(Collectors.groupingBy(EventType::topic, LinkedHashMap::new,
                        Collectors.mapping(EventType::wireName, Collectors.toList())));

        assertThat(eventsByTopic).containsExactly(
                entry("orders.events", List.of("OrderCreated", "OrderConfirmed", "OrderCancelled")),
                entry("inventory.events", List.of("StockReserved", "StockRejected", "StockReleased")),
                entry("payments.events", List.of("PaymentCompleted", "PaymentFailed")));
    }

    @Test
    void everyNameLeadsBackToItsType() {
        for (EventType type : EventType.values()) {
            assertThat(EventType.fromWireName(type.wireName())).isSameAs(type);   // so names are unique, too
        }
    }
}
