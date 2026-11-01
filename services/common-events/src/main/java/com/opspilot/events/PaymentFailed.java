package com.opspilot.events;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

// payment-service → payments.events: the charge failed. Order cancels; inventory releases the held stock.
// reason: e.g. PROVIDER_ERROR (a plain string, like OrderCancelled.reason).
public record PaymentFailed(UUID orderId, UUID paymentId, BigDecimal amount, String reason) implements EventPayload {

    public PaymentFailed {
        Objects.requireNonNull(orderId, "orderId");
    }
}
