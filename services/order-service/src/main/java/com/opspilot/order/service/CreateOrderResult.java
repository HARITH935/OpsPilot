package com.opspilot.order.service;

import com.opspilot.order.model.OrderStatus;

import java.util.UUID;

// What createOrder did. replayed = true means "this Idempotency-Key was seen before; no new order was created".
public record CreateOrderResult(UUID orderId, OrderStatus status, boolean replayed) {
}
