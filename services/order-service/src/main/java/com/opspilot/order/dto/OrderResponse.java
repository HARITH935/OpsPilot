package com.opspilot.order.dto;

import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderItem;
import com.opspilot.order.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Body of GET /api/orders/{id}. totalAmount and unitPrice stay null until inventory prices the order.
public record OrderResponse(
        UUID id,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        String cancelReason,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt
) {

    public record Item(String sku, int qty, BigDecimal unitPrice) {

        static Item from(OrderItem item) {
            return new Item(item.getSku(), item.getQty(), item.getUnitPrice());
        }
    }

    // Must be called inside a transaction: reading order.getItems() may load them from the database
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCancelReason(),
                order.getItems().stream().map(Item::from).toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
