package com.opspilot.payment.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    private UUID orderId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)   // store "COMPLETED", not 0 — readable and safe if the enum is reordered
    private PaymentStatus status;

    private String failureReason;

    // A payment never changes once stored. The version is here because a Long that is still null tells Spring Data
    // "this payment is new" → a plain INSERT, without first SELECTing the (random, assigned) id.
    @Version
    private Long version;

    private Instant createdAt;

    protected Payment() {
        // required by JPA
    }

    private Payment(UUID orderId, BigDecimal amount, PaymentStatus status, String failureReason) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
    }

    // Named ways to create a payment: a completed one can't get a failure reason, a failed one can't lack it
    public static Payment completed(UUID orderId, BigDecimal amount) {
        return new Payment(orderId, amount, PaymentStatus.COMPLETED, null);
    }

    public static Payment failed(UUID orderId, BigDecimal amount, String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("a failed payment needs a failure reason");
        }
        return new Payment(orderId, amount, PaymentStatus.FAILED, failureReason);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
