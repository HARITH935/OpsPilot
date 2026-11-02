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
    void anOrderIsPricedOnlyOnce() {
        Order order = new Order("customer-1");
        order.addItem("SKU-1", 1);
        order.price(Map.of("SKU-1", BigDecimal.ONE), BigDecimal.ONE);

        assertThatThrownBy(() -> order.price(Map.of("SKU-1", BigDecimal.TEN), BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already priced");
        assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void anOrderDecidedBeforeItsPriceArrivedCanStillBePriced() {
        Order confirmed = new Order("customer-1");   // PaymentCompleted was applied before StockReserved
        confirmed.addItem("SKU-1", 2);
        confirmed.confirm();
        Order cancelled = new Order("customer-1");   // PaymentFailed was applied before StockReserved
        cancelled.addItem("SKU-1", 2);
        cancelled.cancel("PAYMENT_FAILED");

        confirmed.price(Map.of("SKU-1", new BigDecimal("4.50")), new BigDecimal("9.00"));
        cancelled.price(Map.of("SKU-1", new BigDecimal("4.50")), new BigDecimal("9.00"));

        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);   // pricing never changes the status
        assertThat(confirmed.getTotalAmount()).isEqualByComparingTo("9.00");
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getItems()).extracting(OrderItem::getUnitPrice).containsExactly(new BigDecimal("4.50"));
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
