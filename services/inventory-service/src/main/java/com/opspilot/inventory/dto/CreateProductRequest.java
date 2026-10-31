package com.opspilot.inventory.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// Body of POST /api/products
public record CreateProductRequest(
        @NotNull @Pattern(regexp = SKU_PATTERN, message = SKU_MESSAGE) String sku,
        @NotBlank @Size(max = 200) String name,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price,   // fits NUMERIC(12,2): whole cents only
        @NotNull @PositiveOrZero @Max(1_000_000) Integer stock
) {

    // A SKU becomes part of a URL (/api/products/SKU-1), so only URL-safe characters are allowed
    public static final String SKU_PATTERN = "[A-Z0-9-]{1,64}";
    public static final String SKU_MESSAGE = "must be 1-64 characters: A-Z, 0-9 or -";
}
