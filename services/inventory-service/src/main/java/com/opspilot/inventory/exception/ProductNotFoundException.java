package com.opspilot.inventory.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String sku) {
        super("Product " + sku + " does not exist");
    }
}
