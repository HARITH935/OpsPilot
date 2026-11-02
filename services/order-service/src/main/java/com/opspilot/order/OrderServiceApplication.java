package com.opspilot.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Turns on auto-configuration and component scanning for com.opspilot.order and its sub-packages
@SpringBootApplication
@ConfigurationPropertiesScan   // also finds @ConfigurationProperties records (SagaProperties) and binds them
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
