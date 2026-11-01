package com.opspilot.events;

import java.util.Objects;
import java.util.UUID;

// order-service → orders.events, after the payment completed: the order is final
public record OrderConfirmed(UUID orderId) implements EventPayload {

    public OrderConfirmed {
        Objects.requireNonNull(orderId, "orderId");
    }
}
