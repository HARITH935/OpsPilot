package com.opspilot.inventory.dto;

import com.opspilot.inventory.model.Product;

import java.math.BigDecimal;

// One product as the API shows it: GET /api/products/{sku}, each row of the list, and the body of a 201
public record ProductResponse(
        String sku,
        String name,
        BigDecimal price,
        int stock,
        int reserved,
        int available   // stock - reserved: what can still be ordered right now
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getSku(), product.getName(), product.getPrice(),
                product.getStock(), product.getReserved(), product.getAvailable());
    }
}
