package com.opspilot.payment.service;

import com.opspilot.payment.config.PaymentProviderProperties;
import com.opspilot.payment.exception.PaymentProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Pretends to be a payment company. Every charge takes {@code latency}, then fails with probability
 * {@code failureRate} (both from PaymentProviderProperties). Chaos scenario C12 raises the failure rate to 0.5.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private final PaymentProviderProperties properties;
    private final DoubleSupplier random;   // returns a number from 0.0 (included) to 1.0 (excluded)

    @Autowired   // two constructors: this is the one Spring uses
    public SimulatedPaymentProvider(PaymentProviderProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());   // a fast random source, safe across threads
    }

    // Tests pass their own "random" numbers, so the outcome is predictable
    SimulatedPaymentProvider(PaymentProviderProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
        log.info("Simulated payment provider: failure rate {}, latency {} ms",
                properties.failureRate(), properties.latency().toMillis());
    }

    @Override
    public void charge(UUID orderId, BigDecimal amount) {
        if (simulatedCallFails()) {
            throw new PaymentProviderException("Simulated provider failure for order " + orderId);
        }
    }

    // The same provider: a refund is just as slow as a charge, and fails just as often
    @Override
    public void refund(UUID orderId, BigDecimal amount) {
        if (simulatedCallFails()) {
            throw new PaymentProviderException("Simulated provider failure refunding order " + orderId);
        }
    }

    private boolean simulatedCallFails() {
        pause(properties.latency());
        // With a rate of 0.3, a random number in [0, 1) is below 0.3 in 30% of the calls
        return random.getAsDouble() < properties.failureRate();
    }

    private static void pause(Duration latency) {
        if (latency.isZero()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // keep the "interrupted" flag for whoever is stopping this thread
            throw new PaymentProviderException("Interrupted while waiting for the provider", e);
        }
    }
}
