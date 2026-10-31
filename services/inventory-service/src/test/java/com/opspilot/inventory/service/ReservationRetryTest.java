package com.opspilot.inventory.service;

import com.opspilot.inventory.config.RetryConfig;
import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import com.opspilot.inventory.repository.ReservationRepository;
import com.opspilot.inventory.service.ReservationResult.Reserved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A tiny Spring context — no database, no web: ReservationService + the retry setup + mocked repositories.
// @Retryable only works through Spring, so this is where its rules (how often, which errors) are checked.
@SpringJUnitConfig({RetryConfig.class, ReservationService.class, ReservationRetryTest.NoTransactions.class})
@RecordApplicationEvents   // collects the events Spring publishes during each test
class ReservationRetryTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final List<RequestedItem> ITEMS = List.of(new RequestedItem("SKU-1", 1));

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private ReservationRepository reservationRepository;

    @BeforeEach
    void productIsInStock() {
        // A fresh product for every attempt, like a real re-read from the database
        when(productRepository.findAllById(any())).thenAnswer(call ->
                List.of(new Product("SKU-1", "Mechanical Keyboard", new BigDecimal("89.99"), 10)));
    }

    @Test
    void conflictIsRetriedAndTheNextAttemptSucceeds() {
        doThrow(new ObjectOptimisticLockingFailureException(Product.class, "SKU-1"))   // 1st attempt: another order won
                .doNothing()                                                          // 2nd attempt: no conflict
                .when(productRepository).flush();

        ReservationResult result = reservationService.reserve(ORDER, ITEMS);

        assertThat(result).isInstanceOf(Reserved.class);
        verify(productRepository, times(2)).flush();
        assertThat(events.stream(MethodRetryEvent.class)).hasSize(1);   // the one failed attempt was reported
    }

    @Test
    void givesUpAfterFiveRetriesAndRethrowsTheConflict() {
        doThrow(new ObjectOptimisticLockingFailureException(Product.class, "SKU-1")).when(productRepository).flush();

        assertThatThrownBy(() -> reservationService.reserve(ORDER, ITEMS))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);   // the original error, not a wrapper
        verify(productRepository, times(6)).flush();                           // 1 attempt + 5 retries
        // One event per failed attempt (6), plus one saying "gave up"
        assertThat(events.stream(MethodRetryEvent.class)).hasSize(7);
        assertThat(events.stream(MethodRetryEvent.class).filter(MethodRetryEvent::isRetryAborted)).hasSize(1);
    }

    @Test
    void otherErrorsAreNotRetried() {
        when(reservationRepository.existsByOrderId(ORDER)).thenReturn(true);   // "already reserved" is not a conflict

        assertThatThrownBy(() -> reservationService.reserve(ORDER, ITEMS))
                .isInstanceOf(IllegalStateException.class);
        verify(reservationRepository, times(1)).existsByOrderId(ORDER);   // tried once, never again
    }

    // ReservationService needs a TransactionOperations; without a database it just runs the code
    @Configuration(proxyBeanMethods = false)
    static class NoTransactions {

        @Bean
        TransactionOperations transactionOperations() {
            return TransactionOperations.withoutTransaction();
        }
    }
}
