package com.opspilot.payment.config;

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

/**
 * payment.provider.resilience.* in application.yml: how every call to the payment provider is protected
 * (ResilientPaymentProvider, architecture doc B2.4). Checked at startup, so a wrong value stops the service.
 */
@ConfigurationProperties(prefix = "payment.provider.resilience")
@Validated
public record ProviderResilienceProperties(
        // How long one call may take; then PROVIDER_TIMEOUT
        @NotNull @DurationMin(millis = 10) @DurationMax(minutes = 1) Duration timeout,
        @NotNull @Valid Retries retry,
        @NotNull @Valid Breaker circuitBreaker,
        @NotNull @Valid Bulkhead bulkhead
) {

    // Retries after PROVIDER_ERROR only: firstWait, then × multiplier each time, ± jitter
    public record Retries(
            @Min(0) @Max(10) int maxRetries,
            @NotNull @DurationMin(millis = 1) Duration firstWait,
            @DecimalMin("1.0") double multiplier,
            @DecimalMin("0.0") @DecimalMax("1.0") double jitter   // 0.5 = each wait 50% shorter … 50% longer, at random
    ) {
    }

    // "Open the circuit when failureRateThreshold % of the last slidingWindowSize payments failed" (B2.4: 50% of 20).
    // A payment counts once, after its retries (ResilientPaymentProvider).
    public record Breaker(
            @Min(1) @Max(100) int failureRateThreshold,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,                        // judge only after this many calls
            @NotNull @DurationMin(seconds = 1) Duration waitInOpenState,    // don't call the provider this long, then test
            @Min(1) int permittedCallsInHalfOpenState                // test calls before deciding: close or open again
    ) {
    }

    // At most maxConcurrentCalls provider calls at once; a further call waits up to maxWait for a free place
    public record Bulkhead(
            @Min(1) int maxConcurrentCalls,
            @NotNull @DurationMin(millis = 0) @DurationMax(seconds = 10) Duration maxWait   // 0 = refuse at once
    ) {
    }
}
