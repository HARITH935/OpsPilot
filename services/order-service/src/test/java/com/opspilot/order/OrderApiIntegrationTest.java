package com.opspilot.order;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.OrderItemRequest;
import com.opspilot.order.model.Order;
import com.opspilot.order.repository.OrderRepository;
import com.opspilot.order.service.CreateOrderResult;
import com.opspilot.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End to end inside one JVM: HTTP request → controller → service → real Postgres (Testcontainers) and back
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void emptyTheDatabase() {
        orderRepository.deleteAllInBatch();   // one DELETE; the database cascades to order_items
    }

    @Test
    void createdOrderIsStoredAndCanBeReadBack() throws Exception {
        String location = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "c-42", "items": [{"sku": "SKU-1", "qty": 2}, {"sku": "SKU-9", "qty": 1}]}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("c-42"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(nullValue()))
                .andExpect(jsonPath("$.items.length()").value(2));

        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        assertThat(orderRepository.existsById(id)).isTrue();
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameOrderAndCreatesNoDuplicate() throws Exception {
        String body = """
                {"customerId": "c-1", "items": [{"sku": "SKU-1", "qty": 2}]}
                """;

        String firstLocation = mockMvc.perform(post("/api/orders").header("Idempotency-Key", "retry-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "retry-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", firstLocation))
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "retry-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "c-1", "items": [{"sku": "SKU-1", "qty": 2}]}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "retry-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "c-1", "items": [{"sku": "SKU-1", "qty": 5}]}
                                """))
                .andExpect(status().isUnprocessableContent());

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("c-1", List.of(new OrderItemRequest("SKU-1", 1)));
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            List<Future<CreateOrderResult>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();   // every thread waits here, then all fire at the same moment
                    return orderService.createOrder(request, "race-key");
                }));
            }
            startTogether.countDown();

            Set<UUID> orderIds = new HashSet<>();
            for (Future<CreateOrderResult> future : futures) {
                orderIds.add(future.get(30, TimeUnit.SECONDS).orderId());
            }
            assertThat(orderIds).hasSize(1);                     // every caller got the same order back
            assertThat(orderRepository.count()).isEqualTo(1);    // and only one row exists
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void listShowsNewestFirstAndFiltersByStatus() throws Exception {
        Order oldest = orderRepository.saveAndFlush(new Order("c-1"));
        Order cancelled = new Order("c-2");
        cancelled.cancel("OUT_OF_STOCK");
        orderRepository.saveAndFlush(cancelled);
        Order newest = orderRepository.saveAndFlush(new Order("c-3"));

        mockMvc.perform(get("/api/orders").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(oldest.getId().toString()));

        mockMvc.perform(get("/api/orders").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }
}
