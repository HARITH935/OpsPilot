package com.opspilot.payment.event;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Payment's part of the order saga (architecture doc B2.3): once inventory has priced and held an order, charge it
@Component
class InventoryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);

    private final PaymentService payments;

    InventoryEventsListener(PaymentService payments) {
        this.payments = payments;
    }

    // Kafka calls this for each message on inventory.events (consumer group payment-service). If it throws, the
    // shared error handler retries 3 times and then moves the message to inventory.events.dlq (MessagingConfiguration).
    @KafkaListener(topics = Topics.INVENTORY)
    void on(String message) {
        EventEnvelope event = EventJson.read(message);   // not a valid event: goes to the dead-letter topic at once
        switch (event.payload()) {
            // Charge exactly the total inventory calculated (ADR 0004: inventory is the price authority)
            case StockReserved reserved -> payments.charge(event.eventId(), reserved.orderId(), reserved.totalAmount());
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());   // nothing to pay
        }
    }
}
