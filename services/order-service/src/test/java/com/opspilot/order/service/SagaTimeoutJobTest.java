package com.opspilot.order.service;

import com.opspilot.order.config.SagaProperties;
import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pure unit test: the repository and OrderService are Mockito mocks. SagaTimeoutIntegrationTest runs the real thing.
@ExtendWith(MockitoExtension.class)
class SagaTimeoutJobTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    private SagaTimeoutJob job;

    @BeforeEach
    void setUp() {
        job = new SagaTimeoutJob(orderRepository, orderService, new SagaProperties(TIMEOUT, Duration.ofSeconds(5)));
    }

    @Test
    void cancelsThePendingOrdersCreatedMoreThanTheTimeoutAgo() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(orderRepository.findIdsByStatusCreatedBefore(eq(OrderStatus.PENDING), any(), any()))
                .thenReturn(List.of(first, second));
        when(orderService.cancelTimedOut(any())).thenReturn(true);

        Instant before = Instant.now();
        assertThat(job.cancelTimedOutOrders()).isEqualTo(2);
        Instant after = Instant.now();

        verify(orderService).cancelTimedOut(first);
        verify(orderService).cancelTimedOut(second);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(orderRepository).findIdsByStatusCreatedBefore(eq(OrderStatus.PENDING), cutoff.capture(), limit.capture());
        assertThat(cutoff.getValue()).isBetween(before.minus(TIMEOUT), after.minus(TIMEOUT));   // "now - 30 s"
        assertThat(limit.getValue().max()).isEqualTo(100);
    }

    @Test
    void aConflictOnOneOrderDoesNotStopTheOthers() {
        UUID paidMeanwhile = UUID.randomUUID();
        UUID stillPending = UUID.randomUUID();
        when(orderRepository.findIdsByStatusCreatedBefore(any(), any(), any()))
                .thenReturn(List.of(paidMeanwhile, stillPending));
        // A payment result saved the first order between the job's read and its save
        when(orderService.cancelTimedOut(paidMeanwhile))
                .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, paidMeanwhile));
        when(orderService.cancelTimedOut(stillPending)).thenReturn(true);

        assertThat(job.cancelTimedOutOrders()).isEqualTo(1);

        verify(orderService).cancelTimedOut(stillPending);
    }

    @Test
    void anOrderDecidedJustBeforeIsNotCounted() {
        UUID confirmedMeanwhile = UUID.randomUUID();
        when(orderRepository.findIdsByStatusCreatedBefore(any(), any(), any())).thenReturn(List.of(confirmedMeanwhile));
        when(orderService.cancelTimedOut(confirmedMeanwhile)).thenReturn(false);   // no longer PENDING when loaded

        assertThat(job.cancelTimedOutOrders()).isZero();
    }
}
