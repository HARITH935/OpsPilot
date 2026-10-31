package com.opspilot.inventory.repository;

import com.opspilot.inventory.model.Reservation;
import com.opspilot.inventory.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

// Both queries filter on order_id, which the unique (order_id, sku) index covers
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    boolean existsByOrderId(UUID orderId);

    List<Reservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);
}
