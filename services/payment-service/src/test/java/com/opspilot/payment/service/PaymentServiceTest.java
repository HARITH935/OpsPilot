package com.opspilot.payment.service;

import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.PaymentRefunded;
import com.opspilot.messaging.consumer.ProcessedEvents;
import com.opspilot.messaging.outbox.OutboxWriter;
import com.opspilot.payment.dto.PageResponse;
import com.opspilot.payment.dto.PaymentResponse;
import com.opspilot.payment.exception.PaymentNotFoundException;
import com.opspilot.payment.exception.PaymentProviderException;
import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;
import com.opspilot.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pure unit test: the repository, the provider, the duplicate check and the outbox are Mockito mocks:
// no Spring, no database, no Kafka
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("209.97");
    private static final UUID EVENT = UUID.randomUUID();   // the StockReserved event being handled

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentProvider paymentProvider;

    @Mock
    private ProcessedEvents processedEvents;

    @Mock
    private OutboxWriter outbox;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // withoutTransaction(): just runs the code — there is no database in this test
        paymentService = new PaymentService(paymentRepository, paymentProvider, processedEvents, outbox,
                TransactionOperations.withoutTransaction());
        lenient().when(processedEvents.markProcessed(EVENT)).thenReturn(true);   // a new event, unless a test says otherwise
    }

    @Test
    void successfulChargeIsStoredAsCompleted() {
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.empty());

        PaymentResponse response = paymentService.charge(EVENT, ORDER, AMOUNT);

        verify(paymentProvider).charge(ORDER, AMOUNT);
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);   // catches what was saved
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(response.id()).isEqualTo(saved.getValue().getId());
        assertThat(response.failureReason()).isNull();
        verify(outbox).write(new PaymentCompleted(ORDER, response.id(), AMOUNT));   // the answer, saved with the payment
    }

    @Test
    void providerFailureIsStoredAsFailedWithAReason() {
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.empty());
        doThrow(new PaymentProviderException("provider down")).when(paymentProvider).charge(ORDER, AMOUNT);

        PaymentResponse response = paymentService.charge(EVENT, ORDER, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo("PROVIDER_ERROR");
        verify(paymentRepository).saveAndFlush(any(Payment.class));   // a failure is recorded too
        verify(outbox).write(new PaymentFailed(ORDER, response.id(), AMOUNT, "PROVIDER_ERROR"));
    }

    @ParameterizedTest   // the reason says why: the saga, the dashboard and the agents can tell these apart
    @ValueSource(strings = {"PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE"})
    void theProvidersReasonIsStoredWithTheFailedPayment(String reason) {
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.empty());
        doThrow(new PaymentProviderException(reason, "no luck", null)).when(paymentProvider).charge(ORDER, AMOUNT);

        PaymentResponse response = paymentService.charge(EVENT, ORDER, AMOUNT);

        assertThat(response.failureReason()).isEqualTo(reason);
        verify(outbox).write(new PaymentFailed(ORDER, response.id(), AMOUNT, reason));
    }

    @Test
    void anOrderThatAlreadyHasAPaymentIsNotChargedAgain() {
        Payment existing = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.charge(EVENT, ORDER, AMOUNT);

        assertThat(response.id()).isEqualTo(existing.getId());
        verifyNoInteractions(paymentProvider, outbox);   // no second charge, no second answer
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void anEventFinishedByAnotherDeliveryMeanwhileIsNotStoredAgain() {
        Payment stored = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findByOrderId(ORDER))
                .thenReturn(Optional.empty())       // 1st look: no payment yet
                .thenReturn(Optional.of(stored));   // the other delivery's payment
        when(processedEvents.markProcessed(EVENT)).thenReturn(false);   // ...which also marked the event

        PaymentResponse response = paymentService.charge(EVENT, ORDER, AMOUNT);

        assertThat(response.id()).isEqualTo(stored.getId());
        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void losingARaceReturnsThePaymentStoredFirst() {
        Payment winner = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findByOrderId(ORDER))
                .thenReturn(Optional.empty())        // 1st look: the other charge isn't stored yet
                .thenReturn(Optional.of(winner));    // after our insert failed: the other charge's payment
        when(paymentRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        PaymentResponse response = paymentService.charge(EVENT, ORDER, AMOUNT);

        assertThat(response.id()).isEqualTo(winner.getId());
        verify(outbox, never()).write(any());   // the loser's answer is not sent (the failed insert came first)
    }

    @Test
    void aRefundGivesTheStoredAmountBackAndIsAnnounced() {
        Payment payment = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refund(EVENT, payment.getId());

        verify(paymentProvider).refund(ORDER, AMOUNT);   // the amount stored with the payment
        verify(paymentRepository).saveAndFlush(payment);
        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(response.refundedAt()).isNotNull();
        verify(outbox).write(new PaymentRefunded(ORDER, payment.getId(), AMOUNT));   // saved with the refund
    }

    @Test
    void anAlreadyRefundedPaymentIsNotRefundedAgain() {
        Payment payment = Payment.completed(ORDER, AMOUNT);
        payment.refund();
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refund(EVENT, payment.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        verifyNoInteractions(paymentProvider, outbox);   // the money goes back once, and is announced once
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void aRefundTheProviderRefusesSavesNothingAndIsLeftToKafkaToRetry() {
        Payment payment = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        doThrow(new PaymentProviderException("provider down")).when(paymentProvider).refund(ORDER, AMOUNT);

        assertThatThrownBy(() -> paymentService.refund(EVENT, payment.getId()))
                .isInstanceOf(PaymentProviderException.class);   // up to the Kafka error handler: retry, then DLQ

        verify(paymentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(processedEvents, outbox);   // the next try starts from scratch
    }

    @Test
    void aFailedPaymentIsNeverRefunded() {
        Payment payment = Payment.failed(ORDER, AMOUNT, "PROVIDER_ERROR");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.refund(EVENT, payment.getId()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(paymentProvider, outbox);   // refused before the provider is called
    }

    @Test
    void getPaymentReturnsThePaymentAsAResponse() {
        Payment payment = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.getPayment(payment.getId());

        assertThat(response.orderId()).isEqualTo(ORDER);
        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void getPaymentThrowsWhenThePaymentDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(id))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void listPaymentsFiltersByStatusNewestFirst() {
        Payment failed = Payment.failed(ORDER, AMOUNT, "PROVIDER_ERROR");
        when(paymentRepository.findByStatus(eq(PaymentStatus.FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(failed), PageRequest.of(0, 20), 1));

        PageResponse<PaymentResponse> page = paymentService.listPayments(PaymentStatus.FAILED, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findByStatus(eq(PaymentStatus.FAILED), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(page.content()).extracting(PaymentResponse::id).containsExactly(failed.getId());
    }

    @Test
    void listPaymentsWithoutStatusReturnsAllPayments() {
        when(paymentRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        paymentService.listPayments(null, 0, 20);

        verify(paymentRepository).findAll(any(Pageable.class));
        verify(paymentRepository, never()).findByStatus(any(), any());
    }
}
