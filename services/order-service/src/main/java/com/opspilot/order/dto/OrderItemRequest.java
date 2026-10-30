package com.opspilot.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderItemRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotNull @Positive @Max(1000) Integer qty   // Integer, not int: a missing qty becomes null and is reported, not silently 0
) {
}
