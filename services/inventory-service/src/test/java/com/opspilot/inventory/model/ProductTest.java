package com.opspilot.inventory.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test: no Spring, no database — runs in milliseconds
class ProductTest {

    @Test
    void newProductHasNothingReservedSoAllStockIsAvailable() {
        Product product = new Product("SKU-X", "Test Product", new BigDecimal("9.99"), 5);

        assertThat(product.getReserved()).isZero();
        assertThat(product.getAvailable()).isEqualTo(5);
    }

    @Test
    void priceMustBeGreaterThanZero() {
        assertThatThrownBy(() -> new Product("SKU-X", "Test Product", BigDecimal.ZERO, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
        assertThatThrownBy(() -> new Product("SKU-X", "Test Product", new BigDecimal("-1.00"), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stockCannotBeNegative() {
        assertThatThrownBy(() -> new Product("SKU-X", "Test Product", new BigDecimal("9.99"), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stock");
    }
}
