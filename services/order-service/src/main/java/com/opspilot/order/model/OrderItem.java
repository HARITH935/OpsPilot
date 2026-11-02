package com.opspilot.order.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // Postgres assigns the id (GENERATED ... AS IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)   // LAZY: don't load the order unless it is used
    @JoinColumn(name = "order_id")
    private Order order;

    private String sku;

    private int qty;

    private BigDecimal unitPrice;

    protected OrderItem() {
        // required by JPA
    }

    OrderItem(Order order, String sku, int qty) {
        this.order = order;
        this.sku = sku;
        this.qty = qty;
    }

    // Only Order.price() sets it: the price comes from inventory, never from the customer
    void price(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
