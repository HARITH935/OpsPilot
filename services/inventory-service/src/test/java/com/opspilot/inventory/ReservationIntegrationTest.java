package com.opspilot.inventory;

import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import com.opspilot.inventory.service.RequestedItem;
import com.opspilot.inventory.service.ReservationResult;
import com.opspilot.inventory.service.ReservationResult.Reason;
import com.opspilot.inventory.service.ReservationResult.Rejected;
import com.opspilot.inventory.service.ReservationResult.Reserved;
import com.opspilot.inventory.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The reservation flow against a real Postgres (Testcontainers): real transactions, real version checks, real retries
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void freshTestProducts() {
        // Reservations point at products (foreign key), so they are deleted first. The seeded demo products stay.
        jdbcTemplate.update("DELETE FROM reservations WHERE sku LIKE 'TEST-%'");
        jdbcTemplate.update("DELETE FROM products WHERE sku LIKE 'TEST-%'");
        productRepository.save(new Product("TEST-A", "Test Product A", new BigDecimal("10.00"), 5));
        productRepository.save(new Product("TEST-B", "Test Product B", new BigDecimal("2.50"), 5));
    }

    @Test
    void twentyOrdersCompetingForFiveUnitsNeverOversell() throws Exception {
        int orders = 20;
        ExecutorService pool = Executors.newFixedThreadPool(orders);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<ReservationResult> results = new ArrayList<>();
        try {
            List<Future<ReservationResult>> futures = new ArrayList<>();
            for (int i = 0; i < orders; i++) {
                // Half the orders list the two products the other way round: the classic recipe for a deadlock
                List<RequestedItem> items = i % 2 == 0
                        ? List.of(new RequestedItem("TEST-A", 1), new RequestedItem("TEST-B", 1))
                        : List.of(new RequestedItem("TEST-B", 1), new RequestedItem("TEST-A", 1));
                UUID orderId = UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    startTogether.await();   // every thread waits here, then all fire at the same moment
                    return reservationService.reserve(orderId, items);
                }));
            }
            startTogether.countDown();

            for (Future<ReservationResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));   // an error in any order fails the test here
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).filteredOn(Reserved.class::isInstance).hasSize(5);
        assertThat(results).filteredOn(Rejected.class::isInstance)
                .extracting(result -> ((Rejected) result).reason())
                .hasSize(15)
                .containsOnly(Reason.OUT_OF_STOCK);
        for (String sku : List.of("TEST-A", "TEST-B")) {
            Product product = productRepository.findById(sku).orElseThrow();
            assertThat(product.getStock()).isEqualTo(5);   // nothing is sold yet, only held
            assertThat(product.getReserved()).isEqualTo(5);
            assertThat(product.getAvailable()).isZero();   // never below zero
            assertThat(heldUnits(sku)).isEqualTo(5);       // the reservation rows agree with the counter
        }
    }

    @Test
    void heldStockIsCommittedWhenPaidAndReleasedWhenNot() {
        UUID paidOrder = UUID.randomUUID();
        UUID unpaidOrder = UUID.randomUUID();
        reservationService.reserve(paidOrder, List.of(new RequestedItem("TEST-A", 2)));
        reservationService.reserve(unpaidOrder, List.of(new RequestedItem("TEST-A", 1)));

        assertThat(reservationService.commit(paidOrder)).isEqualTo(1);
        assertThat(reservationService.release(unpaidOrder)).isEqualTo(1);
        assertThat(reservationService.release(unpaidOrder)).isZero();   // a repeat changes nothing

        Product product = productRepository.findById("TEST-A").orElseThrow();
        assertThat(product.getStock()).isEqualTo(3);       // the 2 paid units left the warehouse
        assertThat(product.getReserved()).isZero();
        assertThat(product.getAvailable()).isEqualTo(3);   // the unpaid unit is back on sale
        assertThat(statusOf(paidOrder)).isEqualTo("COMMITTED");
        assertThat(statusOf(unpaidOrder)).isEqualTo("RELEASED");
    }

    private int heldUnits(String sku) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM reservations WHERE sku = ? AND status = 'HELD'", Integer.class, sku);
    }

    private String statusOf(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE order_id = ?", String.class, orderId);
    }
}
