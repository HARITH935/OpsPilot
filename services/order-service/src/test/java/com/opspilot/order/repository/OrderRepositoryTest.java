package com.opspilot.order.repository;

import com.opspilot.order.TestcontainersConfiguration;
import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

// Loads only the JPA layer (entities, repositories, Flyway) against a real Postgres container.
// Each test runs in a transaction that is rolled back afterwards, so tests don't affect each other.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesOrderWithItemsAndReadsItBack() {
        Order order = new Order("customer-1", "key-1");
        order.addItem("SKU-1", 2);
        order.addItem("SKU-2", 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();   // forget cached objects so the next read really hits the database

        Order loaded = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(loaded.getCustomerId()).isEqualTo("customer-1");
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getItems())
                .extracting("sku", "qty")
                .containsExactlyInAnyOrder(tuple("SKU-1", 2), tuple("SKU-2", 1));
    }

    @Test
    void findsOrdersByStatusOnePageAtATime() {
        for (int i = 0; i < 3; i++) {
            orderRepository.save(new Order("customer-" + i, null));
        }
        Order cancelled = new Order("customer-x", null);
        cancelled.cancel("OUT_OF_STOCK");
        orderRepository.save(cancelled);
        orderRepository.flush();

        Page<Order> firstPage = orderRepository.findByStatus(
                OrderStatus.PENDING, PageRequest.of(0, 2, Sort.by("createdAt").descending()));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);   // the cancelled order is not counted
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        orderRepository.saveAndFlush(new Order("customer-1", "same-key"));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(new Order("customer-2", "same-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        Order order = orderRepository.saveAndFlush(new Order("customer-1", null));   // version 0

        // Simulate another request that updated the same order first (version 0 → 1)
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE orders SET version = version + 1 WHERE id = :id")
                .setParameter("id", order.getId())
                .executeUpdate();

        order.cancel("PAYMENT_FAILED");   // our copy still thinks version = 0

        assertThatThrownBy(() -> orderRepository.saveAndFlush(order))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
