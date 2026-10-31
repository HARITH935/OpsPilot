package com.opspilot.order.dto;

import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One row of GET /api/orders. No items on purpose: loading each order's items would cost one extra query per order (N+1).
public record OrderSummaryResponse(
        UUID id,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt
) {

    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
                order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt());
    }
}
