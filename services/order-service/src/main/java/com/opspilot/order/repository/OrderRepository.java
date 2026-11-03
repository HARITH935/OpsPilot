package com.opspilot.order.repository;

import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Spring Data generates the implementation at startup; the SQL is derived from the method names
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    // Loads the order AND its items in one SQL query (a join) instead of one query for the order + one for the items
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    // For the saga timeout: only the ids (not whole orders) of orders in this status created before the cutoff,
    // oldest first, at most `limit` of them. The (status, created_at) index from V1 makes this a quick index scan.
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :cutoff ORDER BY o.createdAt")
    List<UUID> findIdsByStatusCreatedBefore(OrderStatus status, Instant cutoff, Limit limit);
}
