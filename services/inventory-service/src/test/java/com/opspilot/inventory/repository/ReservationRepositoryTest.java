package com.opspilot.inventory.repository;

import com.opspilot.inventory.TestcontainersConfiguration;
import com.opspilot.inventory.model.Reservation;
import com.opspilot.inventory.model.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

// JPA layer against a real Postgres container (the seeded products exist). Each test is rolled back afterwards.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ReservationRepositoryTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findsAnOrdersReservationsByStatus() {
        UUID orderId = UUID.randomUUID();
        Reservation committed = new Reservation(orderId, "SKU-1", 2);
        committed.commit();
        reservationRepository.save(committed);
        reservationRepository.save(new Reservation(orderId, "SKU-2", 1));
        reservationRepository.save(new Reservation(UUID.randomUUID(), "SKU-1", 5));   // another order
        reservationRepository.flush();
        entityManager.clear();   // forget cached objects so the next read really hits the database

        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.HELD))
                .extracting(Reservation::getSku, Reservation::getQty)
                .containsExactly(tuple("SKU-2", 1));
        assertThat(reservationRepository.existsByOrderId(orderId)).isTrue();
        assertThat(reservationRepository.existsByOrderId(UUID.randomUUID())).isFalse();
    }

    @Test
    void sameProductTwiceForOneOrderIsRefused() {
        UUID orderId = UUID.randomUUID();
        reservationRepository.saveAndFlush(new Reservation(orderId, "SKU-1", 2));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(new Reservation(orderId, "SKU-1", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservations_one_per_order_and_sku");
    }

    @Test
    void reservationForAnUnknownProductIsRefused() {
        assertThatThrownBy(() -> reservationRepository.saveAndFlush(new Reservation(UUID.randomUUID(), "SKU-404", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservations_sku_fkey");   // the foreign key to products
    }
}
