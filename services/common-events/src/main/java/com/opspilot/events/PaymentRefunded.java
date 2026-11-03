package com.opspilot.events;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

// payment-service → payments.events: the money for a cancelled order went back (ADR 0006). Nobody has to react yet;
// it records the refund for the dashboard and the agents later.
public record PaymentRefunded(UUID orderId, UUID paymentId, BigDecimal amount) implements EventPayload {

    public PaymentRefunded {
        Objects.requireNonNull(orderId, "orderId");
    }
}
