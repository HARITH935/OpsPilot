package com.opspilot.gateway;

import com.opspilot.gateway.service.TokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The rate limits, end to end: the whole gateway with a real Redis (Testcontainers) in front of fake services.
// Small limits with a 10 s window, so a test finishes long before its window would slide; Redis is emptied
// before each test, so every test starts with full budgets.
@SpringBootTest(properties = {
        "gateway.demo-users.enabled=true", "gateway.demo-users.password=" + RateLimitIntegrationTest.PASSWORD,
        "gateway.rate-limit.user.requests=5", "gateway.rate-limit.user.window=10s",
        "gateway.rate-limit.anonymous.requests=3", "gateway.rate-limit.anonymous.window=10s",
        "gateway.rate-limit.login.requests=2", "gateway.rate-limit.login.window=10s"})
@AutoConfigureMockMvc
@AutoConfigureMetrics   // so /actuator/prometheus exists
@Import(RedisTestcontainersConfiguration.class)
class RateLimitIntegrationTest {

    static final String PASSWORD = "test-password-for-demo-users";

    private static final FakeService orders = FakeService.start();
    private static final FakeService products = FakeService.start();
    private static final FakeService payments = FakeService.start();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("ORDER_SERVICE_URL", orders::url);
        registry.add("INVENTORY_SERVICE_URL", products::url);
        registry.add("PAYMENT_SERVICE_URL", payments::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenService tokens;   // tokens straight from the service, so the login limit isn't used up by setup

    @BeforeEach
    void fullBudgets() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
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
    void anAnonymousCallerGetsItsBudgetThen429() throws Exception {
        for (int remaining = 2; remaining >= 0; remaining--) {
            mockMvc.perform(get("/api/orders/1").with(from("203.0.113.1")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(remaining)))
                    .andExpect(header().exists("X-RateLimit-Reset"));
        }

        mockMvc.perform(get("/api/orders/1").with(from("203.0.113.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, org.hamcrest.Matchers.matchesPattern("([1-9]|10)")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.detail", containsString("At most 3 requests per 10 s here without logging in")));

        assertThat(orders.received()).hasSize(3);   // the 4th never reached order-service
    }

    @Test
    void aLoggedInUserIsCountedByItsTokenNotItsAddress() throws Exception {
        String admin = token("demo:admin");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/payments").with(from("203.0.113.2")).header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"));   // the user budget, not the IP one
        }
        mockMvc.perform(get("/api/payments").with(from("203.0.113.99")).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isTooManyRequests());   // a new address doesn't help: same user, same budget

        // The same address without a token, and another user from it, each still have their own budget
        mockMvc.perform(get("/api/payments").with(from("203.0.113.2"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/payments").with(from("203.0.113.2"))
                .header(HttpHeaders.AUTHORIZATION, token("demo:viewer"))).andExpect(status().isOk());
    }

    @Test
    void eachRouteHasItsOwnBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/orders").with(from("203.0.113.3"))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/orders").with(from("203.0.113.3"))).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/products").with(from("203.0.113.3"))).andExpect(status().isOk());
    }

    @Test
    void madeUpApiPathsShareOneBudgetSoTheyCantBeUsedToEscapeTheLimit() throws Exception {
        for (String path : List.of("/api/a", "/api/b", "/api/c")) {
            mockMvc.perform(get(path).with(from("203.0.113.4"))).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/d").with(from("203.0.113.4"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void loginHasItsOwnStricterLimit() throws Exception {
        login("not-the-password", "203.0.113.5").andExpect(status().isUnauthorized());
        login("still-not-it", "203.0.113.5").andExpect(status().isUnauthorized());

        // Even the right password is refused now: password guessing gets 2 tries per window, not more
        login(PASSWORD, "203.0.113.5")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "2"));
    }

    @Test
    void aForwardedForHeaderFromTheClientDoesNotBuyANewBudget() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(get("/api/products").with(from("203.0.113.6")).header("X-Forwarded-For", "198.51.100." + i))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/products").with(from("203.0.113.6")).header("X-Forwarded-For", "198.51.100.4"))
                .andExpect(status().isTooManyRequests());   // no trusted proxies: the header is ignored
    }

    @Test
    void healthMetricsAndThePublicKeyAreNeverLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            for (String path : List.of("/actuator/health", "/actuator/prometheus", "/.well-known/jwks.json")) {
                mockMvc.perform(get(path).with(from("203.0.113.7")))
                        .andExpect(status().isOk())
                        .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            }
        }
    }

    @Test
    void refusalsAreCountedForPrometheus() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/api/payments").with(from("203.0.113.8")));
        }

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString("gateway_ratelimit_rejected_total{key_type=\"ip\",route=\"payments\"} 1.0")))
                .andExpect(content().string(containsString("gateway_ratelimit_redis_errors_total 0.0")));
    }

    private String token(String subject) {
        String role = subject.equals("demo:admin") ? "ADMIN" : "VIEWER";
        return "Bearer " + tokens.issue(subject, List.of(role)).value();
    }

    private org.springframework.test.web.servlet.ResultActions login(String password, String address) throws Exception {
        MockHttpServletRequestBuilder request = post("/auth/token").with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}");
        return mockMvc.perform(request);
    }

    // The address the request "comes from" (MockMvc's default is 127.0.0.1)
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
