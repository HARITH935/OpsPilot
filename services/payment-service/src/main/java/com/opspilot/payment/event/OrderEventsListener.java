package com.opspilot.payment.event;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.RefundRequested;
import com.opspilot.events.Topics;
import com.opspilot.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// The saga's compensation for money (ADR 0006): order-service asks for a refund when a payment completed after the
// saga timeout had already cancelled the order
@Component
class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final PaymentService payments;

    OrderEventsListener(PaymentService payments) {
        this.payments = payments;
    }

    // Kafka calls this for each message on orders.events (consumer group payment-service), on its own thread next to
    // InventoryEventsListener. If it throws (e.g. the provider refuses the refund), the shared error handler retries
    // the message 3 times and then moves it to orders.events.dlq (MessagingConfiguration).
    @KafkaListener(topics = Topics.ORDERS)
    void on(String message) {
        EventEnvelope event = EventJson.read(message);   // not a valid event: goes to the dead-letter topic at once
        switch (event.payload()) {
            case RefundRequested refund -> payments.refund(event.eventId(), refund.paymentId());
            // OrderCreated, OrderConfirmed, OrderCancelled: payment starts from StockReserved, not from these
            default -> log.debug("Ignoring {} for order {}", event.eventType(), event.aggregateId());
        }
    }
}
