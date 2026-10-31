package com.opspilot.order.repository;

import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// Spring Data generates the implementation at startup; the SQL is derived from the method names
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    // Loads the order AND its items in one SQL query (a join) instead of one query for the order + one for the items
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);
}
