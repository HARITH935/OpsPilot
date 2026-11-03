package com.opspilot.payment.model;

// A payment is stored with its outcome. Later, a COMPLETED one can become REFUNDED, once (ADR 0006).
public enum PaymentStatus {
    COMPLETED,
    FAILED,
    REFUNDED
}
