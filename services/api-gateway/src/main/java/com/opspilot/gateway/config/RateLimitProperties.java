package com.opspilot.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

// The rate limits, read from gateway.rate-limit.* in application.yml (architecture doc B2.4). Checked at startup.
@ConfigurationProperties(prefix = "gateway.rate-limit")
@Validated
public record RateLimitProperties(
        boolean enabled,
        @NotNull @Valid Limit user,        // a caller with a valid token, counted by the token's "sub", per route
        @NotNull @Valid Limit anonymous,   // a caller without a token, counted by IP address, per route
        @NotNull @Valid Limit login,       // POST /auth/token, counted by IP address: stops password guessing
        List<String> trustedProxies        // proxies whose X-Forwarded-For is believed (ClientIpResolver); none = none
) {

    public RateLimitProperties {
        trustedProxies = trustedProxies == null ? List.of()
                : trustedProxies.stream().map(String::trim).filter(proxy -> !proxy.isEmpty()).toList();
    }

    // "requests per window", e.g. 10 per 1 s
    public record Limit(@Min(1) int requests, @NotNull @DurationMin(millis = 100) Duration window) {
    }
}
