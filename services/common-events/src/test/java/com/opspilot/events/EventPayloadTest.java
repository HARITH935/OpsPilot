package com.opspilot.events;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPayloadTest {

    private static final UUID ORDER = UUID.randomUUID();

    @Test
    void anEventCannotBeChangedAfterwards() {
        List<OrderCreated.Item> items = new ArrayList<>(List.of(new OrderCreated.Item("SKU-1", 2)));
        OrderCreated event = new OrderCreated(ORDER, "c-1", items);

        items.add(new OrderCreated.Item("SKU-2", 1));   // changing the list it was built from...

        assertThat(event.items()).hasSize(1);           // ...doesn't change the event
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyEventNeedsAnOrderId() {
        assertThatThrownBy(() -> new OrderConfirmed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("orderId");
        assertThatThrownBy(() -> new PaymentFailed(null, UUID.randomUUID(), BigDecimal.ONE, "PROVIDER_ERROR"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("orderId");
    }
}
