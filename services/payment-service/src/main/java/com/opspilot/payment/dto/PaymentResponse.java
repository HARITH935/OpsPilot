package com.opspilot.payment.dto;

import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One payment as the API shows it (GET /api/payments/{id} and each row of the list)
public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        PaymentStatus status,
        String failureReason,   // null unless status is FAILED
        Instant createdAt,
        Instant refundedAt      // null unless status is REFUNDED
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(), payment.getOrderId(), payment.getAmount(),
                payment.getStatus(), payment.getFailureReason(), payment.getCreatedAt(), payment.getRefundedAt());
    }
}
