package com.opspilot.inventory.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * inventory.product-cache.* in application.yml: the Redis copy of GET /api/products/{sku} (ProductCache).
 * Checked at startup, so a wrong value stops the service instead of quietly caching for a year.
 */
@ConfigurationProperties(prefix = "inventory.product-cache")
@Validated
public record ProductCacheProperties(
        boolean enabled,   // false = Redis is not used at all: every read goes to Postgres
        // How long a copy is kept. It is also the longest a copy can be out of date (see ProductCache)
        @NotNull @DurationMin(seconds = 1) @DurationMax(hours = 1) Duration ttl
) {
}
