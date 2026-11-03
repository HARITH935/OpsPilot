package com.opspilot.order.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// The saga timeout's two settings, read from order.saga.* in application.yml
// (which takes them from the env vars SAGA_TIMEOUT and SAGA_TIMEOUT_CHECK_INTERVAL).
// @Validated: a 0 s timeout would cancel every new order at once, so it stops the service at startup instead.
@ConfigurationProperties(prefix = "order.saga")
@Validated
public record SagaProperties(
        @NotNull @DurationMin(seconds = 1) Duration timeout,          // a PENDING order older than this is cancelled
        @NotNull @DurationMin(millis = 100) Duration checkInterval    // how often SagaTimeoutJob looks for such orders
) {
}
