package com.opspilot.inventory.event;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.Topics;
import com.opspilot.inventory.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// The saga's last step for inventory (architecture doc B2.3): paid → commit the held stock, not paid → release it
@Component
class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final ReservationService reservations;

    PaymentEventsListener(ReservationService reservations) {
        this.reservations = reservations;
    }

    // Kafka calls this for each message on payments.events (consumer group inventory-service; order reads the same
    // topic in its own group). If it throws, the shared error handler retries 3 times, then payments.events.dlq.
    @KafkaListener(topics = Topics.PAYMENTS)
    void on(String message) {
        EventEnvelope event = EventJson.read(message);   // not a valid event: goes to the dead-letter topic at once
        switch (event.payload()) {
            case PaymentCompleted completed -> reservations.onPaymentCompleted(event.eventId(), completed.orderId());
            case PaymentFailed failed -> reservations.onPaymentFailed(event.eventId(), failed.orderId());
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());   // PaymentRefunded
        }
    }
}
