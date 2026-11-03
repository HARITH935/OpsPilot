package com.opspilot.payment.service;

import com.opspilot.payment.config.PaymentProviderProperties;
import com.opspilot.payment.exception.PaymentProviderException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The failure-rate test: does the simulated provider fail exactly as often as configured? No Spring, no database.
class SimulatedPaymentProviderTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Test
    void zeroFailureRateNeverFails() {
        assertThat(failuresIn(provider(0.0, Duration.ZERO), 1_000)).isZero();
    }

    @Test
    void fullFailureRateAlwaysFails() {
        assertThat(failuresIn(provider(1.0, Duration.ZERO), 1_000)).isEqualTo(1_000);
    }

    @Test
    void failsAboutAsOftenAsConfigured() {
        // A Random with a fixed seed produces the same "random" numbers on every run, so this test can't flake
        SimulatedPaymentProvider provider =
                new SimulatedPaymentProvider(properties(0.3, Duration.ZERO), new Random(42)::nextDouble);

        int failures = failuresIn(provider, 10_000);

        assertThat(failures).isBetween(2_850, 3_150);   // 30% of 10,000, give or take 1.5 percentage points
    }

    @Test
    void failsExactlyWhenTheRandomNumberIsBelowTheRate() {
        assertThat(failuresIn(new SimulatedPaymentProvider(properties(0.3, Duration.ZERO), () -> 0.29), 1)).isOne();
        assertThat(failuresIn(new SimulatedPaymentProvider(properties(0.3, Duration.ZERO), () -> 0.30), 1)).isZero();
    }

    @Test
    void everyChargeTakesTheConfiguredLatency() {
        SimulatedPaymentProvider provider = provider(0.0, Duration.ofMillis(50));

        long start = System.nanoTime();
        provider.charge(ORDER, AMOUNT);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void refundsFailAtTheSameRateAsCharges() {
        assertThatThrownBy(() -> provider(1.0, Duration.ZERO).refund(ORDER, AMOUNT))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("refunding order " + ORDER);
        assertThatCode(() -> provider(0.0, Duration.ZERO).refund(ORDER, AMOUNT)).doesNotThrowAnyException();
        // The same rule as a charge: it fails exactly when the random number is below the rate
        assertThatThrownBy(() -> new SimulatedPaymentProvider(properties(0.3, Duration.ZERO), () -> 0.29)
                .refund(ORDER, AMOUNT)).isInstanceOf(PaymentProviderException.class);
        assertThatCode(() -> new SimulatedPaymentProvider(properties(0.3, Duration.ZERO), () -> 0.30)
                .refund(ORDER, AMOUNT)).doesNotThrowAnyException();
    }

    @Test
    void everyRefundTakesTheConfiguredLatency() {
        SimulatedPaymentProvider provider = provider(0.0, Duration.ofMillis(50));

        long start = System.nanoTime();
        provider.refund(ORDER, AMOUNT);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    private static int failuresIn(SimulatedPaymentProvider provider, int charges) {
        int failures = 0;
        for (int i = 0; i < charges; i++) {
            try {
                provider.charge(ORDER, AMOUNT);
            } catch (PaymentProviderException e) {
                failures++;
            }
        }
        return failures;
    }

    private static SimulatedPaymentProvider provider(double failureRate, Duration latency) {
        return new SimulatedPaymentProvider(properties(failureRate, latency));
    }

    private static PaymentProviderProperties properties(double failureRate, Duration latency) {
        return new PaymentProviderProperties(failureRate, latency);
    }
}
