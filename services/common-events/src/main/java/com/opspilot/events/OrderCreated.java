package com.opspilot.events;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

// order-service → orders.events, when an order is placed. Inventory reacts by reserving the stock.
public record OrderCreated(UUID orderId, String customerId, List<Item> items) implements EventPayload {

    public OrderCreated {
        Objects.requireNonNull(orderId, "orderId");
        items = List.copyOf(items);   // an unchangeable copy: nobody can alter the event once it exists
    }

    public record Item(String sku, int qty) {
    }
}
