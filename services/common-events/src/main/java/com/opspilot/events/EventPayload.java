package com.opspilot.events;

import java.util.UUID;

/**
 * What an event says. Sealed: these eight records are all the events there are, so code that handles events
 * (a switch over them) can see the complete list, and EventTypeTest checks that each one has a name and a topic.
 */
public sealed interface EventPayload
        permits OrderCreated, OrderConfirmed, OrderCancelled,
                StockReserved, StockRejected, StockReleased,
                PaymentCompleted, PaymentFailed {

    // Every saga event is about exactly one order: it becomes the envelope's aggregateId and the Kafka key
    UUID orderId();
}
