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

    @Test
    void reservingHoldsUnitsSoFewerAreAvailable() {
        Product product = productWithStock(5);

        product.reserve(2);

        assertThat(product.getStock()).isEqualTo(5);   // still in the warehouse
        assertThat(product.getReserved()).isEqualTo(2);
        assertThat(product.getAvailable()).isEqualTo(3);
    }

    @Test
    void cannotReserveMoreThanIsAvailable() {
        Product product = productWithStock(5);
        product.reserve(4);

        assertThatThrownBy(() -> product.reserve(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only 1 of SKU-X available");
        assertThat(product.getReserved()).isEqualTo(4);   // unchanged
    }

    @Test
    void releasingMakesHeldUnitsAvailableAgain() {
        Product product = productWithStock(5);
        product.reserve(3);

        product.release(3);

        assertThat(product.getReserved()).isZero();
        assertThat(product.getAvailable()).isEqualTo(5);
    }

    @Test
    void committingTakesHeldUnitsOutOfStock() {
        Product product = productWithStock(5);
        product.reserve(3);

        product.commit(3);

        assertThat(product.getStock()).isEqualTo(2);
        assertThat(product.getReserved()).isZero();
        assertThat(product.getAvailable()).isEqualTo(2);
    }

    @Test
    void cannotReleaseOrCommitMoreThanIsHeld() {
        Product product = productWithStock(5);
        product.reserve(1);

        assertThatThrownBy(() -> product.release(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> product.commit(2)).isInstanceOf(IllegalStateException.class);
        assertThat(product.getStock()).isEqualTo(5);
        assertThat(product.getReserved()).isEqualTo(1);
    }

    @Test
    void quantityMustBeGreaterThanZero() {
        Product product = productWithStock(5);

        assertThatThrownBy(() -> product.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.release(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Product productWithStock(int stock) {
        return new Product("SKU-X", "Test Product", new BigDecimal("9.99"), stock);
    }
}
