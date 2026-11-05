package com.opspilot.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Timeouts, retries and circuit breakers per route (RouteResilience), with the real settings from application.yml
// except: the circuit opens for 1 s instead of 30 s, retries wait 10 ms instead of 100 ms, the products timeout is
// 0.3 s, and the rate limiter is off (these tests fire requests faster than 10 per second).
@SpringBootTest(properties = {
        "gateway.resilience.circuit-breaker.wait-in-open-state=1s",
        "gateway.resilience.retry.first-wait=10ms",
        "gateway.resilience.timeouts.products=300ms",
        "gateway.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureMetrics   // so /actuator/prometheus exists
class ResilienceIntegrationTest {

    private static final FakeService orders = FakeService.start();
    private static final FakeService products = FakeService.start();
    private static final String STOPPED_SERVICE = stoppedServiceUrl();   // payments: connections are refused

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("ORDER_SERVICE_URL", orders::url);
        registry.add("INVENTORY_SERVICE_URL", products::url);
        registry.add("PAYMENT_SERVICE_URL", () -> STOPPED_SERVICE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void reset() {
        orders.reset();
        products.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);   // CLOSED, nothing counted
    }

    @AfterAll
    static void stop() {
        orders.close();
        products.close();
    }

    @Test
    void aGetIsRetriedUntilTheServiceAnswers() throws Exception {
        products.answerInTurn(503, 503, 200);   // e.g. restarting, then back

        mockMvc.perform(get("/api/products/SKU-1")).andExpect(status().isOk());

        assertThat(products.received()).hasSize(3);
    }

    @Test
    void retriesStopAfterThreeAndTheLastAnswerIsPassedOn() throws Exception {
        products.answerInTurn(503, 503, 503, 503, 503);

        mockMvc.perform(get("/api/products/SKU-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":503}"));   // the service's own answer, unchanged

        assertThat(products.received()).hasSize(4);   // the first try + 3 retries
    }

    @Test
    void aPostIsNeverRetried() throws Exception {
        orders.answerInTurn(503, 202);

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(orders.received()).hasSize(1);   // a second try could have created a second order
    }

    @Test
    void a500IsNotRetried() throws Exception {
        products.answerInTurn(500, 200);   // "I tried and failed": trying again won't help

        mockMvc.perform(get("/api/products/SKU-1")).andExpect(status().isInternalServerError());

        assertThat(products.received()).hasSize(1);
    }

    @Test
    void aStoppedServiceIsRetriedThenAnswers503() throws Exception {
        double failedAfterRetries = retryCalls("payments", "failed_with_retry");

        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The service behind /api/payments can't be reached right now"));

        assertThat(retryCalls("payments", "failed_with_retry")).isEqualTo(failedAfterRetries + 1);
    }

    @Test
    void aSlowRouteTimesOutWithoutRetryingAndOtherRoutesKeepTheirOwnTimeout() throws Exception {
        products.answerAfter(Duration.ofSeconds(1), "{}");   // longer than the products route's 0.3 s
        orders.answerAfter(Duration.ofMillis(600), "{}");    // slow too, but within the orders route's 2 s

        long start = System.nanoTime();
        mockMvc.perform(get("/api/products/SKU-1"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.title").value("Service too slow"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(300), Duration.ofMillis(900));
        assertThat(products.received()).hasSize(1);   // not retried: the request may still be running there

        mockMvc.perform(get("/api/orders/1")).andExpect(status().isOk());
    }

    @Test
    void twentyFailuresOpenTheCircuitAndThenCallsAreRefusedAtOnce() throws Exception {
        orders.answer(500, "{}", Map.of());
        for (int i = 0; i < 20; i++) {   // 50% of the last 20 calls is the threshold: here 100%
            mockMvc.perform(get("/api/orders/1")).andExpect(status().isInternalServerError());
        }
        assertThat(circuitBreakers.circuitBreaker("orders").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))   // the 1 s wait of this test (30 s normally)
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(jsonPath("$.detail").value(containsString("failed too often just now")));
        // Refused without waiting: far under the 2 s a call may take. (100 ms was too tight on a busy Linux machine:
        // 2 of 10 runs, step 3.4.) The real proof that the service wasn't asked is the count just below.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(orders.received()).hasSize(20);   // the 21st never reached the service

        mockMvc.perform(get("/api/products/SKU-1")).andExpect(status().isOk());   // other routes are not affected
    }

    @Test
    void clientErrorsDontCountAsFailures() throws Exception {
        orders.answer(404, "{}", Map.of());
        for (int i = 0; i < 25; i++) {
            mockMvc.perform(get("/api/orders/1")).andExpect(status().isNotFound());
        }
        assertThat(circuitBreakers.circuitBreaker("orders").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void afterTheWaitTestCallsCloseTheCircuitAgain() throws Exception {
        orders.answer(500, "{}", Map.of());
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/orders/1"));
        }
        orders.answer(200, "{}", Map.of());   // the service has recovered

        CircuitBreaker breaker = circuitBreakers.circuitBreaker("orders");
        await().atMost(Duration.ofSeconds(3)).until(() -> breaker.getState() == CircuitBreaker.State.HALF_OPEN);
        for (int i = 0; i < 3; i++) {   // the 3 test calls
            mockMvc.perform(get("/api/orders/1")).andExpect(status().isOk());
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theStateOfEachRouteIsInTheMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString(
                        "resilience4j_circuitbreaker_state{name=\"orders\",state=\"closed\"} 1.0")))
                .andExpect(content().string(containsString(
                        "resilience4j_circuitbreaker_state{name=\"payments\",state=\"open\"} 0.0")));
    }

    // Resilience4j keeps the count itself; Micrometer only reads it (a FunctionCounter, not a Counter)
    private double retryCalls(String route, String kind) {
        FunctionCounter counter = meters.find("resilience4j.retry.calls").tag("name", route).tag("kind", kind)
                .functionCounter();
        return counter == null ? 0 : counter.count();
    }

    private static String stoppedServiceUrl() {
        FakeService service = FakeService.start();
        String url = service.url();
        service.close();
        return url;
    }
}
