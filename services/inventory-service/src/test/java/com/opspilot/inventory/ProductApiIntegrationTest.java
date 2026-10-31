package com.opspilot.inventory;

import com.opspilot.inventory.dto.CreateProductRequest;
import com.opspilot.inventory.dto.ProductResponse;
import com.opspilot.inventory.exception.ProductAlreadyExistsException;
import com.opspilot.inventory.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End to end inside one JVM: HTTP request → controller → service → real Postgres (Testcontainers) and back.
// The catalogue starts with the 10 demo products from the V2 seed migration.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void removeProductsCreatedByTests() {
        jdbcTemplate.update("DELETE FROM products WHERE sku LIKE 'TEST-%'");   // keeps the seeded demo products
    }

    @Test
    void seededCatalogueIsListedSortedBySku() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10))
                .andExpect(jsonPath("$.totalPages").value(4))
                // sorted as text, character by character: "SKU-10" comes before "SKU-2"
                .andExpect(jsonPath("$.content[*].sku", contains("SKU-1", "SKU-10", "SKU-2")));
    }

    @Test
    void createdProductCanBeReadBack() throws Exception {
        String location = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "TEST-1", "name": "Test Product", "price": 12.50, "stock": 7}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.price").value(12.5))
                .andExpect(jsonPath("$.stock").value(7))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void duplicateSkuIsRefusedAndTheOriginalIsUnchanged() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku": "SKU-1", "name": "Imposter", "price": 1.00, "stock": 1}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/products/SKU-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(89.99));
    }

    @Test
    void concurrentCreatesOfTheSameSkuLetExactlyOneWin() throws Exception {
        CreateProductRequest request = new CreateProductRequest("TEST-RACE", "Race Product", new BigDecimal("5.00"), 10);
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            List<Future<ProductResponse>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();   // every thread waits here, then all fire at the same moment
                    return productService.createProduct(request);
                }));
            }
            startTogether.countDown();

            int created = 0;
            int refused = 0;
            for (Future<ProductResponse> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    created++;
                } catch (ExecutionException e) {
                    // Every loser gets the clean "already exists" error (→ 409), never a raw database error (→ 500)
                    assertThat(e.getCause()).isInstanceOf(ProductAlreadyExistsException.class);
                    refused++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(refused).isEqualTo(requests - 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
