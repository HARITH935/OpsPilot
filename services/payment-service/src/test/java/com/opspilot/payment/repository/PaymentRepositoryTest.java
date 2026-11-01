package com.opspilot.payment.repository;

import com.opspilot.payment.TestcontainersConfiguration;
import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Loads only the JPA layer (entities, repositories, Flyway) against a real Postgres container.
// Each test runs in a transaction that is rolled back afterwards, so tests don't affect each other.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class PaymentRepositoryTest {

    private static final BigDecimal AMOUNT = new BigDecimal("209.97");

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesPaymentAndFindsItByOrder() {
        UUID orderId = UUID.randomUUID();
        Payment payment = paymentRepository.saveAndFlush(Payment.failed(orderId, AMOUNT, "PROVIDER_ERROR"));
        entityManager.clear();   // forget cached objects so the next read really hits the database

        Payment loaded = paymentRepository.findByOrderId(orderId).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(payment.getId());
        assertThat(loaded.getAmount()).isEqualByComparingTo("209.97");
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(loaded.getFailureReason()).isEqualTo("PROVIDER_ERROR");
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void secondPaymentForTheSameOrderIsRefused() {
        UUID orderId = UUID.randomUUID();
        paymentRepository.saveAndFlush(Payment.completed(orderId, AMOUNT));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(Payment.completed(orderId, AMOUNT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_order_id_key");   // the UNIQUE (order_id) constraint
    }

    @Test
    void findsPaymentsByStatusOnePageAtATime() {
        for (int i = 0; i < 3; i++) {
            paymentRepository.save(Payment.completed(UUID.randomUUID(), AMOUNT));
        }
        paymentRepository.save(Payment.failed(UUID.randomUUID(), AMOUNT, "PROVIDER_ERROR"));
        paymentRepository.flush();

        Page<Payment> firstPage = paymentRepository.findByStatus(
                PaymentStatus.COMPLETED, PageRequest.of(0, 2, Sort.by("createdAt").descending()));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);   // the failed payment is not counted
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void databaseRefusesAFailedPaymentWithoutAReason() {
        // Even SQL written by hand (bypassing the Java checks) cannot break the rule: the CHECK constraint refuses it
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO payments (id, order_id, amount, status, failure_reason, version, created_at)
                        VALUES (gen_random_uuid(), gen_random_uuid(), 10.00, 'FAILED', NULL, 0, now())
                        """)
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("payments_reason_only_when_failed");
    }
}
