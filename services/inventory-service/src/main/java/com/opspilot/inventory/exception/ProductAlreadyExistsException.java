package com.opspilot.inventory.exception;

// POST /api/products with a SKU that is already in the catalogue → 409 Conflict
public class ProductAlreadyExistsException extends RuntimeException {

    public ProductAlreadyExistsException(String sku) {
        super("Product " + sku + " already exists");
    }
}
