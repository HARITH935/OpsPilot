package com.opspilot.payment.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test: no Spring, no database — runs in milliseconds
class PaymentTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("209.97");

    @Test
    void completedPaymentHasNoFailureReason() {
        Payment payment = Payment.completed(ORDER, AMOUNT);

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getFailureReason()).isNull();
    }

    @Test
    void failedPaymentKeepsItsReason() {
        Payment payment = Payment.failed(ORDER, AMOUNT, "PROVIDER_ERROR");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void failedPaymentNeedsAReason() {
        assertThatThrownBy(() -> Payment.failed(ORDER, AMOUNT, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedPaymentCanBeRefundedOnce() {
        Payment payment = Payment.completed(ORDER, AMOUNT);

        payment.refund();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        assertThatThrownBy(payment::refund)   // the money can only go back once
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is REFUNDED");
    }

    @Test
    void failedPaymentCannotBeRefunded() {
        Payment payment = Payment.failed(ORDER, AMOUNT, "PROVIDER_ERROR");

        assertThatThrownBy(payment::refund)   // no money was taken, so there is nothing to give back
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is FAILED");
        assertThat(payment.getRefundedAt()).isNull();
    }

    @Test
    void amountMustBeGreaterThanZero() {
        assertThatThrownBy(() -> Payment.completed(ORDER, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
