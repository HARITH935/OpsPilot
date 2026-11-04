package com.opspilot.gateway.filter;

import com.opspilot.gateway.config.ResilienceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// RouteResilience without Spring: what it sets up per route at startup, and what it does between two attempts
class RouteResilienceTest {

    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
    private final TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    @Test
    void eachRouteGetsItsOwnTimeoutAndItsInstancesExistFromTheStart() {
        new RouteResilience(circuitBreakers, timeLimiters, retries, calls,
                properties(Map.of("products", Duration.ofMillis(300))), routes("orders", "products"));

        assertThat(timeLimiters.timeLimiter("products").getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofMillis(300));
        assertThat(timeLimiters.timeLimiter("orders").getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(2));   // the default timeout
        assertThat(circuitBreakers.getAllCircuitBreakers()).hasSize(2);   // so their metrics show before any traffic
        assertThat(retries.getAllRetries()).hasSize(2);
    }

    @Test
    void aTimeoutForARouteThatDoesNotExistStopsTheStart() {
        assertThatThrownBy(() -> new RouteResilience(circuitBreakers, timeLimiters, retries, calls,
                properties(Map.of("ordrs", Duration.ofSeconds(1))), routes("orders", "products")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.resilience.timeouts.ordrs");
    }

    @Test
    void beforeARetryThePreviousAnswerIsClosed() throws Exception {
        RetryRegistry retryOn503 = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryOnResult(result -> ((ServerResponse) result).statusCode().value() == 503)
                .build());
        RouteResilience resilience = new RouteResilience(circuitBreakers, timeLimiters, retryOn503, calls,
                properties(Map.of()), routes("products"));
        ServerRequest request = ServerRequest.create(new MockHttpServletRequest("GET", "/api/products/SKU-1"), List.of());
        ClientHttpResponse firstAnswer = mock(ClientHttpResponse.class);
        AtomicInteger attempts = new AtomicInteger();

        ServerResponse response = resilience.handle("products", request, req -> {
            if (attempts.incrementAndGet() == 1) {
                MvcUtils.putAttribute(req, MvcUtils.CLIENT_RESPONSE_ATTR, firstAnswer);   // as the gateway's proxy does
                return ServerResponse.status(503).build();
            }
            return ServerResponse.ok().build();
        });

        assertThat(response.statusCode().value()).isEqualTo(200);
        verify(firstAnswer).close();   // its connection is freed, not left waiting for a body nobody reads
    }

    private static ResilienceProperties properties(Map<String, Duration> timeouts) {
        return new ResilienceProperties(
                new ResilienceProperties.Breaker(50, 20, 20, Duration.ofSeconds(30), 3),
                new ResilienceProperties.Retries(3, Duration.ofMillis(100), 2, 0.5),
                Duration.ofSeconds(2), timeouts);
    }

    private static GatewayMvcProperties routes(String... ids) {
        GatewayMvcProperties gateway = new GatewayMvcProperties();
        for (String id : ids) {
            RouteProperties route = new RouteProperties();
            route.setId(id);
            gateway.getRoutes().add(route);
        }
        return gateway;
    }
}
