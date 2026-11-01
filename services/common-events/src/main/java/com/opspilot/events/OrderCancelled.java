package com.opspilot.events;

import java.util.Objects;
import java.util.UUID;

// order-service → orders.events, when the order is cancelled. Inventory releases any stock still held for it.
// reason: e.g. OUT_OF_STOCK, UNKNOWN_PRODUCT, PAYMENT_FAILED, TIMEOUT. A plain string (not an enum), so a new
// reason doesn't break services that don't know it yet.
public record OrderCancelled(UUID orderId, String reason) implements EventPayload {

    public OrderCancelled {
        Objects.requireNonNull(orderId, "orderId");
    }
}
