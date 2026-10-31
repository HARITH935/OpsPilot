package com.opspilot.order.model;

import org.junit.jupiter.api.Test;

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
