package com.opspilot.payment.repository;

import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// Spring Data generates the implementation at startup; the SQL is derived from the method names
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);   // uses the unique index on order_id

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
}
