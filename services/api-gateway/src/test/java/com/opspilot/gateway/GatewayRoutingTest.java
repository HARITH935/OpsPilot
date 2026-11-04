package com.opspilot.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The whole gateway (real routes, real HTTP client) in front of three fake services on local ports. The products
// route's timeout is shortened to 0.5 s, so the "too slow" test doesn't wait 2 s.
@SpringBootTest(properties = "gateway.resilience.timeouts.products=500ms")
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfiguration.class)   // the rate limiter needs a Redis
class GatewayRoutingTest {

    private static final FakeService orders = FakeService.start();
    private static final FakeService products = FakeService.start();
    private static final FakeService payments = FakeService.start();

    // Point the three routes at the fakes: the same env vars a real deployment sets (ORDER_SERVICE_URL, ...)
    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("ORDER_SERVICE_URL", orders::url);
        registry.add("INVENTORY_SERVICE_URL", products::url);
        registry.add("PAYMENT_SERVICE_URL", payments::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        orders.reset();
        products.reset();
        payments.reset();
    }

    @AfterAll
    static void stop() {
        orders.close();
        products.close();
        payments.close();
    }

    @Test
    void eachPathGoesToItsOwnServiceUnchanged() throws Exception {
        mockMvc.perform(get("/api/orders/7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products").param("page", "0").param("size", "5")).andExpect(status().isOk());
        mockMvc.perform(get("/api/payments").param("status", "REFUNDED")).andExpect(status().isOk());

        assertThat(orders.received()).extracting(FakeService.Received::pathAndQuery)
                .containsExactly("/api/orders/7f1c2a4e-1b2c-4d3e-8f9a-0b1c2d3e4f5a");
        assertThat(products.received()).extracting(FakeService.Received::pathAndQuery)
                .containsExactly("/api/products?page=0&size=5");
        assertThat(payments.received()).extracting(FakeService.Received::pathAndQuery)
                .containsExactly("/api/payments?status=REFUNDED");
    }

    @Test
    void theCollectionPathWithoutATrailingPartMatchesToo() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isOk());   // Path=/api/orders/** includes /api/orders

        assertThat(orders.lastReceived().pathAndQuery()).isEqualTo("/api/orders");
    }

    @Test
    void aPostIsPassedOnWithItsBodyAndHeadersAndTheAnswerComesBackUnchanged() throws Exception {
        String body = "{\"customerId\":\"c-1\",\"items\":[{\"sku\":\"SKU-1\",\"qty\":2}]}";
        orders.answer(202, "{\"orderId\":\"o-1\",\"status\":\"PENDING\"}", Map.of("Location", "/api/orders/o-1"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "retry-safe-1")
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/orders/o-1"))   // relative: still right behind the gateway
                .andExpect(jsonPath("$.orderId").value("o-1"));

        FakeService.Received sent = orders.lastReceived();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.body()).isEqualTo(body);
        assertThat(sent.header("Idempotency-Key")).isEqualTo("retry-safe-1");
        assertThat(sent.header("Content-Type")).startsWith("application/json");
        assertThat(sent.header("Upgrade")).isNull();   // plain HTTP/1.1 (HttpClientConfig), no HTTP/2 upgrade attempt
    }

    @Test
    void anErrorFromTheServiceComesBackUnchanged() throws Exception {
        String problem = "{\"title\":\"Order not found\",\"status\":404}";
        orders.answer(404, "application/problem+json", problem, Map.of());

        mockMvc.perform(get("/api/orders/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().json(problem));   // the service's own words, not the gateway's
    }

    @Test
    void aPathNoRouteMatchesIsNotFoundAndReachesNoService() throws Exception {
        mockMvc.perform(get("/api/shipments/1"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(orders.received()).isEmpty();
        assertThat(products.received()).isEmpty();
        assertThat(payments.received()).isEmpty();
    }

    @Test
    void aServiceThatAnswersTooSlowlyGives504() throws Exception {
        products.answerAfter(Duration.ofMillis(1500), "{}");   // longer than the route's 0.5 s timeout

        long start = System.nanoTime();
        mockMvc.perform(get("/api/products/SKU-1"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Service too slow"))
                .andExpect(jsonPath("$.detail").value("The service behind /api/products/SKU-1 did not answer in time"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isBetween(Duration.ofMillis(500), Duration.ofMillis(1400));   // gave up at the limit, didn't wait
    }
}
