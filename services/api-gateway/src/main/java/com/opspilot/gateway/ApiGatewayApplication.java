package com.opspilot.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Turns on auto-configuration and component scanning for com.opspilot.gateway. The routes themselves are not code:
// they are listed in application.yml, and Spring Cloud Gateway builds them at startup.
@SpringBootApplication
@ConfigurationPropertiesScan   // also finds the @ConfigurationProperties records (JwtProperties, DemoUsersProperties)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
