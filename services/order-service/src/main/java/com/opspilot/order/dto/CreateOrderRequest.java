package com.opspilot.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

// Body of POST /api/orders. No prices here on purpose: inventory prices the order (ADR 0004).
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotEmpty @Size(max = 50) @Valid List<OrderItemRequest> items   // @Valid: also check each item's own rules
) {
}
