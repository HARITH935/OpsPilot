package com.opspilot.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// inventory-service → inventory.events: every item is held and priced (inventory prices orders, ADR 0004).
// Order copies the prices onto the order; payment charges totalAmount.
public record StockReserved(UUID orderId, List<Item> items, BigDecimal totalAmount) implements EventPayload {

    public StockReserved {
        Objects.requireNonNull(orderId, "orderId");
        items = List.copyOf(items);   // an unchangeable copy: nobody can alter the event once it exists
    }

    public record Item(String sku, int qty, BigDecimal unitPrice) {
    }
}
