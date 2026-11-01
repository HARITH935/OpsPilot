package com.opspilot.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Turns on auto-configuration and component scanning for com.opspilot.payment and its sub-packages
@SpringBootApplication
@ConfigurationPropertiesScan   // also finds @ConfigurationProperties records (PaymentProviderProperties) and binds them
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
