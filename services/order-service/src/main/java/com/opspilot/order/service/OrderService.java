package com.opspilot.order.service;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.dto.OrderSummaryResponse;
import com.opspilot.order.dto.PageResponse;
import com.opspilot.order.exception.IdempotencyKeyReusedException;
import com.opspilot.order.exception.OrderNotFoundException;
import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final TransactionOperations transactions;

    public OrderService(OrderRepository orderRepository, TransactionOperations transactions) {
        this.orderRepository = orderRepository;
        this.transactions = transactions;   // Spring Boot provides a TransactionTemplate: "run this block in a transaction"
    }

    /**
     * Creates an order. With an Idempotency-Key, a retry of the same request returns the original order
     * instead of creating a second one; reusing the key for a different request throws IdempotencyKeyReusedException.
     * Deliberately not @Transactional: the insert runs in its own transaction so a duplicate-key failure can be caught here.
     */
    public CreateOrderResult createOrder(CreateOrderRequest request, String idempotencyKey) {
        if (idempotencyKey == null) {
            return transactions.execute(tx -> insert(request, null, null));
        }

        String fingerprint = RequestFingerprint.of(request);
        Optional<CreateOrderResult> replay = findReplay(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return transactions.execute(tx -> insert(request, idempotencyKey, fingerprint));
        } catch (DataIntegrityViolationException e) {
            // Race: two requests with this key both found nothing and both inserted; the unique constraint let one win
            log.info("Idempotency-Key {} was used concurrently; returning the order that won", idempotencyKey);
            return findReplay(idempotencyKey, fingerprint).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)   // read-only: no dirty checking, and the items can be read while mapping
    public OrderResponse getOrder(UUID id) {
        return orderRepository.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listOrders(OrderStatus status, int page, int size) {
        // Newest first; the id breaks ties so pages stay stable when two orders share a timestamp
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Order> orders = status == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return PageResponse.from(orders.map(OrderSummaryResponse::from));
    }

    private CreateOrderResult insert(CreateOrderRequest request, String idempotencyKey, String fingerprint) {
        Order order = new Order(request.customerId(), idempotencyKey, fingerprint);
        request.items().forEach(item -> order.addItem(item.sku(), item.qty()));
        orderRepository.saveAndFlush(order);   // flush now, so a duplicate key fails here — inside the try/catch above

        log.info("Order {} created for customer {} with {} item(s)",
                order.getId(), order.getCustomerId(), order.getItems().size());
        return new CreateOrderResult(order.getId(), order.getStatus(), false);
    }

    private Optional<CreateOrderResult> findReplay(String idempotencyKey, String fingerprint) {
        return orderRepository.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new CreateOrderResult(existing.getId(), existing.getStatus(), true);
        });
    }
}
