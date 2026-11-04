package com.opspilot.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Turns on auto-configuration and component scanning for com.opspilot.inventory and its sub-packages
@SpringBootApplication
@ConfigurationPropertiesScan   // also finds @ConfigurationProperties records (ProductCacheProperties) and binds them
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
