package com.opspilot.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The orders route points at a port where nothing listens any more, as if order-service had crashed
@SpringBootTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfiguration.class)   // the rate limiter needs a Redis
class GatewayServiceDownTest {

    private static final String STOPPED_SERVICE = stoppedServiceUrl();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("ORDER_SERVICE_URL", () -> STOPPED_SERVICE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aServiceThatIsDownGives503WithoutRevealingItsAddress() throws Exception {
        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(jsonPath("$.detail").value("The service behind /api/orders/1 can't be reached right now"))
                .andExpect(content().string(not(containsString(STOPPED_SERVICE))));   // internal address stays internal
    }

    // A fake service that was started (so the port was free) and stopped again: connections are now refused
    private static String stoppedServiceUrl() {
        FakeService service = FakeService.start();
        String url = service.url();
        service.close();
        return url;
    }
}
