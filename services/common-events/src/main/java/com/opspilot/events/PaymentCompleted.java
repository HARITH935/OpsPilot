package com.opspilot.events;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

// payment-service → payments.events: the money was taken. Order confirms; inventory commits the held stock.
public record PaymentCompleted(UUID orderId, UUID paymentId, BigDecimal amount) implements EventPayload {

    public PaymentCompleted {
        Objects.requireNonNull(orderId, "orderId");
    }
}
