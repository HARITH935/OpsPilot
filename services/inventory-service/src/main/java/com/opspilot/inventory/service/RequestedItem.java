package com.opspilot.inventory.service;

// One line of an order, as inventory receives it (from the OrderCreated event in Phase 2)
public record RequestedItem(String sku, int qty) {
}
