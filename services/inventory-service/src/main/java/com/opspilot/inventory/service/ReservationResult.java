package com.opspilot.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// The answer to "hold the stock for this order". Sealed: it is always one of the two records below, nothing else.
// In Phase 2 they become the StockReserved / StockRejected events.
public sealed interface ReservationResult {

    UUID orderId();

    // Every item is held. The prices come from the catalogue: inventory prices the order (ADR 0004).
    record Reserved(UUID orderId, List<Item> items, BigDecimal totalAmount) implements ReservationResult {

        public record Item(String sku, int qty, BigDecimal unitPrice) {
        }
    }

    // Nothing is held, because this product is unknown or doesn't have enough available
    record Rejected(UUID orderId, String sku, Reason reason) implements ReservationResult {
    }

    enum Reason {
        UNKNOWN_PRODUCT,
        OUT_OF_STOCK
    }
}
