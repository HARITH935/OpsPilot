package com.opspilot.order.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test: no Spring, no database — runs in milliseconds
class OrderTest {

    @Test
    void newOrderIsPending() {
        Order order = new Order("customer-1");

        assertThat(order.getId()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void pendingOrderCanBeConfirmed() {
        Order order = new Order("customer-1");

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void pendingOrderCanBeCancelledWithReason() {
        Order order = new Order("customer-1");

        order.cancel("OUT_OF_STOCK");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void pricingCopiesEachItemsUnitPriceAndTheTotal() {
        Order order = new Order("customer-1");
        order.addItem("SKU-1", 2);
        order.addItem("SKU-2", 1);

        order.price(Map.of("SKU-1", new BigDecimal("89.99"), "SKU-2", new BigDecimal("29.99")), new BigDecimal("209.97"));

        assertThat(order.getItems()).extracting(OrderItem::getUnitPrice)
                .containsExactly(new BigDecimal("89.99"), new BigDecimal("29.99"));
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("209.97"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);   // priced, but not paid yet
    }

    @Test
    void aMissingPriceChangesNothing() {
        Order order = new Order("customer-1");
        order.addItem("SKU-1", 2);
        order.addItem("SKU-2", 1);

        assertThatThrownBy(() -> order.price(Map.of("SKU-1", new BigDecimal("89.99")), new BigDecimal("179.98")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU-2");
        assertThat(order.getItems()).extracting(OrderItem::getUnitPrice).containsOnlyNulls();   // all or nothing
        assertThat(order.getTotalAmount()).isNull();
    }

    @Test
    void anOrderIsPricedOnlyOnceAndOnlyWhilePending() {
        Order priced = new Order("customer-1");
        priced.addItem("SKU-1", 1);
        priced.price(Map.of("SKU-1", BigDecimal.ONE), BigDecimal.ONE);
        Order cancelled = new Order("customer-1");
        cancelled.addItem("SKU-1", 1);
        cancelled.cancel("OUT_OF_STOCK");

        assertThatThrownBy(() -> priced.price(Map.of("SKU-1", BigDecimal.TEN), BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already priced");
        assertThatThrownBy(() -> cancelled.price(Map.of("SKU-1", BigDecimal.ONE), BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already CANCELLED");
    }

    @Test
    void decidedOrderCannotChangeAgain() {
        Order order = new Order("customer-1");
        order.confirm();

        assertThatThrownBy(() -> order.cancel("PAYMENT_FAILED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already CONFIRMED");
    }

    @Test
    void itemsCanOnlyBeAddedThroughTheOrder() {
        Order order = new Order("customer-1");
        order.addItem("SKU-1", 2);

        assertThat(order.getItems()).hasSize(1);
        assertThatThrownBy(() -> order.getItems().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void idempotencyKeyAndFingerprintMustComeTogether() {
        assertThatThrownBy(() -> new Order("customer-1", "key-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order("customer-1", null, "fingerprint"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
