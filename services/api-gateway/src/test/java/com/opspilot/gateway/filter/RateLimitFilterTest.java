package com.opspilot.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Plain unit test: which budget a path belongs to (or none)
class RateLimitFilterTest {

    @Test
    void eachRouteIsNamedAfterItsFirstPathPart() {
        assertThat(RateLimitFilter.routeOf("/api/orders")).isEqualTo("orders");
        assertThat(RateLimitFilter.routeOf("/api/orders/7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a")).isEqualTo("orders");
        assertThat(RateLimitFilter.routeOf("/api/products/SKU-1")).isEqualTo("products");
        assertThat(RateLimitFilter.routeOf("/api/payments")).isEqualTo("payments");
    }

    @Test
    void loginHasItsOwnBudget() {
        assertThat(RateLimitFilter.routeOf("/auth/token")).isEqualTo("login");
    }

    @Test
    void anyOtherApiPathSharesOneBudget() {
        assertThat(RateLimitFilter.routeOf("/api/shipments/1")).isEqualTo("other");
        assertThat(RateLimitFilter.routeOf("/api/made-up-" + System.nanoTime())).isEqualTo("other");
        assertThat(RateLimitFilter.routeOf("/api/")).isEqualTo("other");
    }

    @Test
    void healthMetricsAndThePublicKeyAreNotLimited() {
        assertThat(RateLimitFilter.routeOf("/actuator/health/readiness")).isNull();
        assertThat(RateLimitFilter.routeOf("/actuator/prometheus")).isNull();
        assertThat(RateLimitFilter.routeOf("/.well-known/jwks.json")).isNull();
    }
}
