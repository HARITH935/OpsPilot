package com.opspilot.order.service;

import com.opspilot.order.config.SagaProperties;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The saga's safety net (architecture doc B2.3): every order must end CONFIRMED or CANCELLED within 30 s. If no
 * result comes (inventory or payment down or slow, an event stuck), this job cancels the order with reason TIMEOUT.
 *
 * <p>Each order is cancelled in its own small transaction, so one problem order doesn't hold up the rest. Safe with
 * several copies of order-service running: if two of them, or a copy and a payment result, change the same order at
 * once, its @Version lets only the first one save. The loser skips that order.
 */
@Component
public class SagaTimeoutJob {

    static final int BATCH_SIZE = 100;   // orders per run, at most; the next run takes the rest

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutJob.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final SagaProperties properties;

    public SagaTimeoutJob(OrderRepository orderRepository, OrderService orderService, SagaProperties properties) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.properties = properties;
    }

    /**
     * Cancels the PENDING orders created more than the timeout ago and returns how many it cancelled. Runs on its own
     * (scheduling is on in MessagingConfig); tests call it directly.
     */
    @Scheduled(fixedDelayString = "${order.saga.check-interval}")   // time between the end of one run and the next
    public int cancelTimedOutOrders() {
        Instant cutoff = Instant.now().minus(properties.timeout());
        int cancelled = 0;
        for (UUID orderId : orderRepository.findIdsByStatusCreatedBefore(OrderStatus.PENDING, cutoff, Limit.of(BATCH_SIZE))) {
            try {
                if (orderService.cancelTimedOut(orderId)) {
                    cancelled++;
                }
            } catch (OptimisticLockingFailureException e) {
                // The order changed between our read and our save. That change stands: a payment result that just
                // arrived wins over the timeout, and if another copy of this job cancelled it, it is cancelled anyway.
                log.info("Order {} changed while being timed out, leaving it as it is", orderId);
            }
        }
        return cancelled;
    }
}
