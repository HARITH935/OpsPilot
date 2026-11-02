package com.opspilot.inventory.event;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderCreated;
import com.opspilot.events.Topics;
import com.opspilot.inventory.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Inventory's part of the order saga (architecture doc B2.3): it reacts to the events order-service sends
@Component
class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final ReservationService reservations;

    OrderEventsListener(ReservationService reservations) {
        this.reservations = reservations;
    }

    // Kafka calls this for each message on orders.events. If it throws, the shared error handler retries the
    // message 3 times and then moves it to orders.events.dlq (MessagingConfiguration).
    @KafkaListener(topics = Topics.ORDERS)
    void on(String message) {
        EventEnvelope event = EventJson.read(message);   // not a valid event: goes to the dead-letter topic at once
        switch (event.payload()) {
            case OrderCreated created -> reservations.reserveForOrder(event.eventId(), created);
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());   // step 2.5
        }
    }
}
