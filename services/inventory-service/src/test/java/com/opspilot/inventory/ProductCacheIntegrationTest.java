package com.opspilot.inventory;

import com.opspilot.inventory.dto.CreateProductRequest;
import com.opspilot.inventory.dto.ProductResponse;
import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import com.opspilot.inventory.service.ProductService;
import com.opspilot.inventory.service.RequestedItem;
import com.opspilot.inventory.service.ReservationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The product cache inside the whole service: real Postgres, real Redis, real transactions
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class ProductCacheIntegrationTest {

    private static final String SKU = "TEST-CACHE";
    private static final String KEY = "inventory:product:v1:" + SKU;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductService productService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void freshTestProduct() {
        jdbcTemplate.update("DELETE FROM reservations WHERE sku = ?", SKU);
        jdbcTemplate.update("DELETE FROM products WHERE sku = ?", SKU);
        redis.delete(KEY);   // the SQL above goes around the evictor
        productRepository.save(new Product(SKU, "Cache Test Product", new BigDecimal("12.50"), 10));
    }

    @Test
    void theSecondReadIsAHitWithExactlyTheSameAnswer() throws Exception {
        double hits = count("hit");
        double misses = count("miss");

        String first = mockMvc.perform(get("/api/products/" + SKU)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(count("miss")).isEqualTo(misses + 1);
        assertThat(redis.getExpire(KEY, TimeUnit.SECONDS)).isBetween(55L, 60L);   // kept for the 60 s TTL

        String second = mockMvc.perform(get("/api/products/" + SKU)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(count("hit")).isEqualTo(hits + 1);
        assertThat(second).isEqualTo(first).contains("12.50");   // the copy, byte for byte, price scale included
    }

    @Test
    void aReservationDeletesTheCopySoTheNextReadShowsTheNewStock() {
        assertThat(productService.getProduct(SKU).reserved()).isZero();
        assertThat(redis.hasKey(KEY)).isTrue();

        reservationService.reserve(UUID.randomUUID(), List.of(new RequestedItem(SKU, 3)));   // its own transaction

        assertThat(redis.hasKey(KEY)).isFalse();
        ProductResponse after = productService.getProduct(SKU);
        assertThat(after.reserved()).isEqualTo(3);
        assertThat(after.available()).isEqualTo(7);
    }

    @Test
    void aReadDuringAChangeCannotLeaveAnOldCopyBehind() {
        transactions.executeWithoutResult(tx -> {
            productRepository.findById(SKU).orElseThrow().reserve(1);
            productRepository.flush();   // the UPDATE is sent, but not committed yet

            // Meanwhile another request reads the product. It can't see the uncommitted change, so it puts the old
            // values into the cache. (Its own thread: this thread is inside the transaction and would see the change.)
            ProductResponse duringTheChange =
                    CompletableFuture.supplyAsync(() -> productService.getProduct(SKU)).join();
            assertThat(duringTheChange.reserved()).isZero();
            assertThat(redis.hasKey(KEY)).isTrue();
        });

        // The commit deleted that old copy. (Deleting already at the UPDATE would have been too early.)
        assertThat(redis.hasKey(KEY)).isFalse();
        assertThat(productService.getProduct(SKU).reserved()).isEqualTo(1);
    }

    @Test
    void aRolledBackChangeKeepsTheCopy() {
        productService.getProduct(SKU);

        transactions.executeWithoutResult(tx -> {
            productRepository.findById(SKU).orElseThrow().reserve(1);
            productRepository.flush();
            tx.setRollbackOnly();   // e.g. a later step of the same transaction failed
        });

        assertThat(redis.hasKey(KEY)).isTrue();   // nothing changed, so the copy is still right
        assertThat(productService.getProduct(SKU).reserved()).isZero();
    }

    @Test
    void aProductCreatedAgainUnderAnOldSkuDoesNotShowTheOldCopy() {
        productService.getProduct(SKU);   // copy of "Cache Test Product"
        jdbcTemplate.update("DELETE FROM products WHERE sku = ?", SKU);   // plain SQL: the copy stays...

        productService.createProduct(new CreateProductRequest(SKU, "Brand New Product", new BigDecimal("3.00"), 1));

        assertThat(productService.getProduct(SKU).name()).isEqualTo("Brand New Product");   // ...until the INSERT
    }

    private double count(String result) {
        Counter counter = meters.find("cache.requests").tag("cache", "products").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
