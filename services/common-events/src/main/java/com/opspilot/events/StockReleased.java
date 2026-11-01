package com.opspilot.events;

import java.util.Objects;
import java.util.UUID;

// inventory-service → inventory.events: the stock held for this order is on sale again (the saga's compensation step)
public record StockReleased(UUID orderId) implements EventPayload {

    public StockReleased {
        Objects.requireNonNull(orderId, "orderId");
    }
}
