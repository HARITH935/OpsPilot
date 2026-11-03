package com.opspilot.events;

import java.util.Objects;
import java.util.UUID;

// order-service → orders.events: this payment completed after its order was already cancelled (saga timeout), so the
// money must go back. Payment refunds it and answers PaymentRefunded (ADR 0006). The amount is not in the message:
// payment refunds the amount it stored for the payment.
public record RefundRequested(UUID orderId, UUID paymentId) implements EventPayload {

    public RefundRequested {
        Objects.requireNonNull(orderId, "orderId");
    }
}
