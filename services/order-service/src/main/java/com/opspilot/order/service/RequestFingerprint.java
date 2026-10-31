package com.opspilot.order.service;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.OrderItemRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * SHA-256 "fingerprint" of a create-order request, stored next to its Idempotency-Key.
 * Same content → same fingerprint, so a genuine retry can be told apart from a different request reusing the key.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(CreateOrderRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.customerId());
        request.items().stream()
                // The order of the items doesn't change what was ordered, so sort them before hashing
                .sorted(Comparator.comparing(OrderItemRequest::sku).thenComparing(OrderItemRequest::qty))
                .forEach(item -> {
                    append(canonical, item.sku());
                    append(canonical, String.valueOf(item.qty()));
                });
        return sha256Hex(canonical.toString());
    }

    // Length prefix ("5:SKU-1") keeps the boundaries clear: plain "c-1S"+"KU-1" and "c-1"+"SKU-1" would look the same
    private static void append(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is available on every Java platform", e);
        }
    }
}
