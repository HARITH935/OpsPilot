package com.opspilot.events;

import java.util.Objects;
import java.util.UUID;

// inventory-service → inventory.events: nothing was held, because this product is unknown or short. Order cancels.
// reason: UNKNOWN_PRODUCT or OUT_OF_STOCK (a plain string, like OrderCancelled.reason).
public record StockRejected(UUID orderId, String sku, String reason) implements EventPayload {

    public StockRejected {
        Objects.requireNonNull(orderId, "orderId");
    }
}
