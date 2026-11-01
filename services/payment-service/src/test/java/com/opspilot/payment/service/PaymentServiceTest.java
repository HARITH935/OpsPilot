package com.opspilot.payment.service;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pure unit test: the repository and the provider are Mockito mocks, so there is no Spring and no database
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("209.97");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentProvider paymentProvider;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, paymentProvider);
    }

    @Test
    void successfulChargeIsStoredAsCompleted() {
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.empty());

        PaymentResponse response = paymentService.charge(ORDER, AMOUNT);

        verify(paymentProvider).charge(ORDER, AMOUNT);
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);   // catches what was saved
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(response.id()).isEqualTo(saved.getValue().getId());
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void providerFailureIsStoredAsFailedWithAReason() {
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.empty());
        doThrow(new PaymentProviderException("provider down")).when(paymentProvider).charge(ORDER, AMOUNT);

        PaymentResponse response = paymentService.charge(ORDER, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo(PaymentService.PROVIDER_ERROR);
        verify(paymentRepository).saveAndFlush(any(Payment.class));   // a failure is recorded too
    }

    @Test
    void anOrderThatAlreadyHasAPaymentIsNotChargedAgain() {
        Payment existing = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findByOrderId(ORDER)).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.charge(ORDER, AMOUNT);

        assertThat(response.id()).isEqualTo(existing.getId());
        verifyNoInteractions(paymentProvider);   // no second charge
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void losingARaceReturnsThePaymentStoredFirst() {
        Payment winner = Payment.completed(ORDER, AMOUNT);
        when(paymentRepository.findByOrderId(ORDER))
                .thenReturn(Optional.empty())        // 1st look: the other charge isn't stored yet
                .thenReturn(Optional.of(winner));    // after our insert failed: the other charge's payment
        when(paymentRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        PaymentResponse response = paymentService.charge(ORDER, AMOUNT);

        assertThat(response.id()).isEqualTo(winner.getId());
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
