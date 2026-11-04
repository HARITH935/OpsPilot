package com.opspilot.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * gateway.resilience.* in application.yml: how each route protects itself from a slow or broken service
 * (architecture doc B2.4). Checked at startup, so a wrong value stops the gateway instead of misbehaving later.
 */
@ConfigurationProperties(prefix = "gateway.resilience")
@Validated
public record ResilienceProperties(
        @NotNull @Valid Breaker circuitBreaker,
        @NotNull @Valid Retries retry,
        // How long a route waits for its service's answer, unless set for that route under "timeouts"
        @NotNull @DurationMin(millis = 10) @DurationMax(minutes = 1) Duration timeout,
        Map<String, @NotNull @DurationMin(millis = 10) @DurationMax(minutes = 1) Duration> timeouts   // by route id
) {

    public ResilienceProperties {
        timeouts = timeouts == null ? Map.of() : Map.copyOf(timeouts);
    }

    public Duration timeoutFor(String routeId) {
        return timeouts.getOrDefault(routeId, timeout);
    }

    // "Open the circuit when failureRateThreshold % of the last slidingWindowSize calls failed" (B2.4: 50% of 20)
    public record Breaker(
            @Min(1) @Max(100) int failureRateThreshold,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,                       // judge only after this many calls
            @NotNull @DurationMin(seconds = 1) Duration waitInOpenState,   // refuse everything this long, then test
            @Min(1) int permittedCallsInHalfOpenState               // test calls before deciding: close or open again
    ) {
    }

    // Retries for GET / HEAD that couldn't reach the service: firstWait, then × multiplier each time, ± jitter
    public record Retries(
            @Min(0) @Max(10) int maxRetries,
            @NotNull @DurationMin(millis = 1) Duration firstWait,
            @DecimalMin("1.0") double multiplier,
            @DecimalMin("0.0") @DecimalMax("1.0") double jitter   // 0.5 = each wait is 50% shorter … 50% longer, at random
    ) {
    }
}
