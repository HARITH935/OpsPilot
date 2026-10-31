package com.opspilot.order.service;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.OrderItemRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void sameRequestGivesTheSameFingerprint() {
        String first = RequestFingerprint.of(request("c-1", item("SKU-1", 2)));
        String second = RequestFingerprint.of(request("c-1", item("SKU-1", 2)));

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");   // SHA-256 = 64 hex characters
    }

    @Test
    void orderOfTheItemsDoesNotMatter() {
        assertThat(RequestFingerprint.of(request("c-1", item("SKU-1", 2), item("SKU-2", 1))))
                .isEqualTo(RequestFingerprint.of(request("c-1", item("SKU-2", 1), item("SKU-1", 2))));
    }

    @Test
    void anyRealChangeGivesADifferentFingerprint() {
        String original = RequestFingerprint.of(request("c-1", item("SKU-1", 2)));

        assertThat(RequestFingerprint.of(request("c-2", item("SKU-1", 2)))).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(request("c-1", item("SKU-1", 3)))).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(request("c-1", item("SKU-9", 2)))).isNotEqualTo(original);
    }

    @Test
    void valuesCannotBleedIntoEachOther() {
        // Plain concatenation would turn both into "c-1SKU-12"; the length prefixes keep them apart
        assertThat(RequestFingerprint.of(request("c-1S", item("KU-1", 2))))
                .isNotEqualTo(RequestFingerprint.of(request("c-1", item("SKU-1", 2))));
    }

    private static CreateOrderRequest request(String customerId, OrderItemRequest... items) {
        return new CreateOrderRequest(customerId, List.of(items));
    }

    private static OrderItemRequest item(String sku, int qty) {
        return new OrderItemRequest(sku, qty);
    }
}
