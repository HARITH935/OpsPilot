package com.opspilot.inventory.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Units of one product held for one order. No @Version here: every status change also updates the product row
// in the same transaction, and the product's version already stops two transactions from settling the same stock.
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // Postgres assigns the id (GENERATED ... AS IDENTITY)
    private Long id;

    private UUID orderId;

    private String sku;

    private int qty;

    @Enumerated(EnumType.STRING)   // store "HELD", not 0 — readable and safe if the enum is reordered
    private ReservationStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    protected Reservation() {
        // required by JPA
    }

    public Reservation(UUID orderId, String sku, int qty) {
        this.orderId = orderId;
        this.sku = sku;
        this.qty = qty;
        this.status = ReservationStatus.HELD;
    }

    public void commit() {
        requireHeld();
        this.status = ReservationStatus.COMMITTED;
    }

    public void release() {
        requireHeld();
        this.status = ReservationStatus.RELEASED;
    }

    // A reservation is settled exactly once: HELD → COMMITTED or HELD → RELEASED
    private void requireHeld() {
        if (status != ReservationStatus.HELD) {
            throw new IllegalStateException("Reservation of " + sku + " for order " + orderId + " is already " + status);
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getSku() {
        return sku;
    }

    public int getQty() {
        return qty;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
