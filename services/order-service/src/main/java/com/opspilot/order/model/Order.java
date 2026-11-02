package com.opspilot.order.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "orders")   // "order" is a reserved SQL word, so the table is "orders"
public class Order {

    @Id
    private UUID id;

    private String customerId;

    @Enumerated(EnumType.STRING)   // store "PENDING", not 0 — readable and safe if the enum is reordered
    private OrderStatus status;

    private BigDecimal totalAmount;

    private String cancelReason;

    private String idempotencyKey;

    private String requestFingerprint;

    @Version   // Hibernate adds "WHERE version = ?" to every UPDATE; a stale copy fails instead of overwriting
    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    // Items are saved/deleted together with their order; removing one from the list deletes its row
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // required by JPA
    }

    public Order(String customerId) {
        this(customerId, null, null);
    }

    // The Idempotency-Key and the fingerprint of the request that used it always come as a pair (or both null)
    public Order(String customerId, String idempotencyKey, String requestFingerprint) {
        if ((idempotencyKey == null) != (requestFingerprint == null)) {
            throw new IllegalArgumentException("idempotencyKey and requestFingerprint must be given together");
        }
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = OrderStatus.PENDING;
    }

    public void addItem(String sku, int qty) {
        items.add(new OrderItem(this, sku, qty));
    }

    /**
     * Inventory priced the order (StockReserved, ADR 0004): every item gets its unit price, the order inventory's
     * total. All or nothing, only once, and only while the order is still PENDING.
     */
    public void price(Map<String, BigDecimal> unitPriceBySku, BigDecimal total) {
        requirePending();
        if (totalAmount != null) {
            throw new IllegalStateException("Order " + id + " is already priced");
        }
        for (OrderItem item : items) {   // check every item first, so a missing price changes nothing
            if (!unitPriceBySku.containsKey(item.getSku())) {
                throw new IllegalArgumentException("No price for " + item.getSku() + " in order " + id);
            }
        }
        items.forEach(item -> item.price(unitPriceBySku.get(item.getSku())));
        this.totalAmount = total;
    }

    public void confirm() {
        requirePending();
        this.status = OrderStatus.CONFIRMED;
    }

    public void cancel(String reason) {
        requirePending();
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
    }

    // An order is decided exactly once: PENDING → CONFIRMED or PENDING → CANCELLED
    private void requirePending() {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("Order " + id + " is already " + status);
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

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);   // callers must use addItem(), not list.add()
    }
}
