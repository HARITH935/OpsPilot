package com.opspilot.inventory.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test: no Spring, no database — runs in milliseconds
class ReservationTest {

    private static final UUID ORDER = UUID.randomUUID();

    @Test
    void newReservationIsHeld() {
        assertThat(new Reservation(ORDER, "SKU-1", 2).getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void heldReservationCanBeCommittedOrReleased() {
        Reservation paid = new Reservation(ORDER, "SKU-1", 2);
        Reservation unpaid = new Reservation(ORDER, "SKU-2", 1);

        paid.commit();
        unpaid.release();

        assertThat(paid.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(unpaid.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void settledReservationCannotChangeAgain() {
        Reservation reservation = new Reservation(ORDER, "SKU-1", 2);
        reservation.commit();

        assertThatThrownBy(reservation::release)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already COMMITTED");
    }
}
