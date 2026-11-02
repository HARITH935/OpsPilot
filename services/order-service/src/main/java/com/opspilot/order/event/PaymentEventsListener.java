package com.opspilot.order.event;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.Topics;
import com.opspilot.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// The saga's last step for order (architecture doc B2.3): the payment result decides the order
@Component
class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final OrderService orders;

    PaymentEventsListener(OrderService orders) {
        this.orders = orders;
    }

    // Kafka calls this for each message on payments.events (consumer group order-service; inventory reads the same
    // topic in its own group). It runs on its own thread, next to InventoryEventsListener: if both change the same
    // order at once, @Version makes one fail, and the shared error handler retries it (then payments.events.dlq).
    @KafkaListener(topics = Topics.PAYMENTS)
    void on(String message) {
        EventEnvelope event = EventJson.read(message);   // not a valid event: goes to the dead-letter topic at once
        switch (event.payload()) {
            case PaymentCompleted completed -> orders.onPaymentCompleted(event.eventId(), completed);
            case PaymentFailed failed -> orders.onPaymentFailed(event.eventId(), failed);
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());
        }
    }
}
