package com.opspilot.inventory.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {

    @Id   // the SKU itself is the key (a "natural key"), so there is no generated id
    private String sku;

    private String name;

    private BigDecimal price;

    private int stock;      // units on hand in the warehouse

    private int reserved;   // units held for orders that are not paid yet (step 1.6)

    // Optimistic locking counter. A Long that is still null tells Spring Data "this product is new" → INSERT.
    // So saving a new product with an existing SKU fails on the primary key instead of overwriting that product.
    @Version
    private Long version;

    protected Product() {
        // required by JPA
    }

    public Product(String sku, String name, BigDecimal price, int stock) {
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be greater than 0");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("stock must not be negative");
        }
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    // What can still be ordered right now
    public int getAvailable() {
        return stock - reserved;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public int getReserved() {
        return reserved;
    }

    public Long getVersion() {
        return version;
    }
}
