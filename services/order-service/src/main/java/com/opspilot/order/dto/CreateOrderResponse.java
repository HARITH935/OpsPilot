package com.opspilot.order.dto;

import com.opspilot.order.model.OrderStatus;

import java.util.UUID;

// Returned with 202 Accepted: the order exists, but the saga (stock, payment) has not finished yet
public record CreateOrderResponse(UUID orderId, OrderStatus status) {
}
