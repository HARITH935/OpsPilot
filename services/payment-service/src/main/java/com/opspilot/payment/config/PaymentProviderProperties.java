package com.opspilot.payment.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// The two knobs of the simulated payment provider, read from payment.provider.* in application.yml
// (which takes them from the env vars PAYMENT_FAILURE_RATE and PAYMENT_LATENCY_MS).
// @Validated: an invalid value stops the service at startup instead of misbehaving later.
@ConfigurationProperties(prefix = "payment.provider")
@Validated
public record PaymentProviderProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,   // share of charges that fail
        @NotNull @DurationMin(millis = 0) Duration latency           // how long every charge takes
) {
}
