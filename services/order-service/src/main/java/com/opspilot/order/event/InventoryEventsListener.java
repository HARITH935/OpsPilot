package com.opspilot.order.event;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.StockRejected;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Order's part of the saga (architecture doc B2.3): it learns from inventory whether the order can be fulfilled
@Component
class InventoryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);

    private final OrderService orders;

    InventoryEventsListener(OrderService orders) {
        this.orders = orders;
    }

    // Kafka calls this for each message on inventory.events (consumer group order-service; payment-service reads the
    // same topic in its own group). If it throws, the shared error handler retries 3 times, then inventory.events.dlq.
    @KafkaListener(topics = Topics.INVENTORY)
    void on(String message) {
        EventEnvelope event = EventJson.read(message);   // not a valid event: goes to the dead-letter topic at once
        switch (event.payload()) {
            case StockReserved reserved -> orders.onStockReserved(event.eventId(), reserved);
            case StockRejected rejected -> orders.onStockRejected(event.eventId(), rejected);
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());
        }
    }
}
