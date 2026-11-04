package com.opspilot.payment.service;

import com.opspilot.payment.config.ProviderResilienceProperties;
import com.opspilot.payment.exception.PaymentProviderException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// The protection around the payment provider, against a fake provider the test controls. No Spring, no Kafka.
class ResilientPaymentProviderTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("49.90");

    private final FakeProvider fake = new FakeProvider();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        fake.release.countDown();   // never leave a blocked fake call behind
        calls.shutdownNow();
    }

    @Test
    void aProviderErrorIsRetriedUntilTheChargeWorks() {
        fake.failuresLeft.set(2);

        provider(settings()).charge(ORDER, AMOUNT);   // no exception: the third try worked

        assertThat(fake.calls).hasValue(3);
    }

    @Test
    void afterTwoRetriesTheErrorIsPassedOn() {
        fake.failuresLeft.set(100);

        assertThatThrownBy(() -> provider(settings()).charge(ORDER, AMOUNT))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_ERROR"));
        assertThat(fake.calls).hasValue(3);   // the first try + 2 retries
    }

    @Test
    void aSlowProviderTimesOutAndIsNotRetried() {
        fake.delay = Duration.ofSeconds(2);
        ResilientPaymentProvider provider = provider(settings().withTimeout(Duration.ofMillis(200)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_TIMEOUT"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(200), Duration.ofMillis(800));
        assertThat(fake.calls).hasValue(1);   // a timeout is "unknown": the money may be gone, so never charge again
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(fake.interrupted).hasValue(1));
    }

    @Test
    void twentyFailuresOpenTheCircuitAndThenTheProviderIsNotCalled() {
        fake.failuresLeft.set(100);
        ResilientPaymentProvider provider = provider(settings().withoutRetries());
        for (int i = 0; i < 20; i++) {   // 50% of the last 20 calls is the threshold: here 100%
            assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT)).isInstanceOf(PaymentProviderException.class);
        }
        assertThat(provider.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_UNAVAILABLE"));
        assertThat(fake.calls).hasValue(20);   // the 21st never reached the provider
    }

    @Test
    void aPaymentThatWorksOnARetryCountsAsASuccess() {
        // Two of every three attempts fail (worse than chaos C12): each payment needs all three tries, but gets through
        fake.succeedEvery = 3;
        ResilientPaymentProvider provider = provider(settings());

        for (int i = 0; i < 25; i++) {
            provider.charge(ORDER, AMOUNT);
        }

        assertThat(fake.calls).hasValue(75);
        // The breaker judges payments, not attempts: counting attempts (67% failed) would have opened it
        assertThat(provider.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aProviderThatIsFullyDownOpensTheCircuitAfterTwentyPayments() {
        fake.failuresLeft.set(1000);
        ResilientPaymentProvider provider = provider(settings());
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT)).isInstanceOf(PaymentProviderException.class);
        }
        assertThat(provider.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(fake.calls).hasValue(60);   // 20 payments × 3 tries

        assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_UNAVAILABLE"));
        assertThat(fake.calls).hasValue(60);   // refused without a single try, and without retries
    }

    @Test
    void afterTheWaitTestCallsCloseTheCircuitAgain() {
        fake.failuresLeft.set(20);
        ResilientPaymentProvider provider = provider(settings().withoutRetries());
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT)).isInstanceOf(PaymentProviderException.class);
        }
        // the provider works again (failuresLeft is used up)

        await().atMost(Duration.ofSeconds(3)).until(() -> provider.circuitState() == CircuitBreaker.State.HALF_OPEN);
        for (int i = 0; i < 3; i++) {   // the 3 test calls
            provider.charge(ORDER, AMOUNT);
        }
        assertThat(provider.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theBulkheadLetsOnlySoManyCallsRunAtOnce() throws Exception {
        fake.block = true;   // every call hangs until the test releases it
        ResilientPaymentProvider provider = provider(settings().withBulkhead(2, Duration.ZERO));

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> provider.charge(ORDER, AMOUNT));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> provider.charge(ORDER, AMOUNT));
        await().atMost(Duration.ofSeconds(2)).until(() -> fake.running.get() == 2);

        assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT))   // a third one: no place left, no waiting
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_UNAVAILABLE"));

        fake.release.countDown();
        first.get(2, TimeUnit.SECONDS);   // the two inside finish normally
        second.get(2, TimeUnit.SECONDS);
        assertThat(fake.maxRunning).hasValue(2);
        assertThat(fake.calls).hasValue(2);
    }

    @Test
    void aFullBulkheadDoesNotCountAgainstTheProvider() throws Exception {
        fake.block = true;
        ResilientPaymentProvider provider = provider(settings().withBulkhead(1, Duration.ZERO));
        CompletableFuture<Void> inside = CompletableFuture.runAsync(() -> provider.charge(ORDER, AMOUNT));
        await().atMost(Duration.ofSeconds(2)).until(() -> fake.running.get() == 1);

        for (int i = 0; i < 25; i++) {   // more than enough refusals to open the circuit, if they counted
            assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT)).isInstanceOf(PaymentProviderException.class);
        }
        fake.release.countDown();
        inside.get(2, TimeUnit.SECONDS);

        assertThat(provider.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);   // our own limit, not its fault
    }

    @Test
    void aCallTheTimeoutGaveUpOnStillHoldsItsPlace() {
        // Some network clients don't stop when interrupted: the call goes on although nobody waits for it any more.
        // The bulkhead sits inside the timeout, so such a call keeps its place: a hanging provider can't pile up calls.
        fake.block = true;
        fake.ignoreInterrupts = true;
        ResilientPaymentProvider provider =
                provider(settings().withTimeout(Duration.ofMillis(100)).withBulkhead(1, Duration.ZERO));

        assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_TIMEOUT"));
        assertThat(fake.running).hasValue(1);   // still running, after the timeout

        assertThatThrownBy(() -> provider.charge(ORDER, AMOUNT))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo("PROVIDER_UNAVAILABLE"));
        assertThat(fake.maxRunning).hasValue(1);   // never two at once
    }

    @Test
    void refundsAreProtectedToo() {
        fake.failuresLeft.set(2);

        provider(settings()).refund(ORDER, AMOUNT);

        assertThat(fake.calls).hasValue(3);
    }

    @Test
    void aBugIsPassedOnAsItIsAndNotRetried() {
        fake.bug = true;

        assertThatThrownBy(() -> provider(settings()).charge(ORDER, AMOUNT))
                .isInstanceOf(IllegalStateException.class)   // not a FAILED payment: Kafka retries it, then the DLQ
                .isNotInstanceOf(PaymentProviderException.class);
        assertThat(fake.calls).hasValue(1);
    }

    @Test
    void theMetricsCarryTheProvidersName() {
        provider(settings());

        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "payment-provider").gauges()).isNotEmpty();
        assertThat(meters.find("resilience4j.bulkhead.available.concurrent.calls").tag("name", "payment-provider")
                .gauge()).isNotNull();
    }

    private ResilientPaymentProvider provider(Settings settings) {
        return new ResilientPaymentProvider(fake, settings.toProperties(), calls, meters);
    }

    private static Settings settings() {   // application.yml's values, but a 1 s open circuit (30 s normally)
        return new Settings(Duration.ofSeconds(2), 2, 5, Duration.ofSeconds(1));
    }

    private record Settings(Duration timeout, int maxRetries, int maxConcurrentCalls, Duration maxWait) {

        Settings withTimeout(Duration timeout) {
            return new Settings(timeout, maxRetries, maxConcurrentCalls, maxWait);
        }

        Settings withoutRetries() {
            return new Settings(timeout, 0, maxConcurrentCalls, maxWait);
        }

        Settings withBulkhead(int maxConcurrentCalls, Duration maxWait) {
            return new Settings(timeout, maxRetries, maxConcurrentCalls, maxWait);
        }

        ProviderResilienceProperties toProperties() {
            return new ProviderResilienceProperties(timeout,
                    new ProviderResilienceProperties.Retries(maxRetries, Duration.ofMillis(10), 2, 0.5),
                    new ProviderResilienceProperties.Breaker(50, 20, 20, Duration.ofSeconds(1), 3),
                    new ProviderResilienceProperties.Bulkhead(maxConcurrentCalls, maxWait));
        }
    }

    // A provider that fails, hangs or breaks on request, and counts what happens to it
    private static final class FakeProvider implements PaymentProvider {

        final AtomicInteger failuresLeft = new AtomicInteger();   // the next N calls fail with PROVIDER_ERROR
        volatile int succeedEvery;   // e.g. 3: only every third call works, the others fail with PROVIDER_ERROR
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final AtomicInteger interrupted = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        volatile Duration delay = Duration.ZERO;
        volatile boolean block;
        volatile boolean ignoreInterrupts;   // with block: keep waiting for release even when interrupted
        volatile boolean bug;

        @Override
        public void charge(UUID orderId, BigDecimal amount) {
            call();
        }

        @Override
        public void refund(UUID orderId, BigDecimal amount) {
            call();
        }

        private void call() {
            int call = calls.incrementAndGet();
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                if (block && ignoreInterrupts) {
                    awaitIgnoringInterrupts();
                } else if (block) {
                    release.await();
                }
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();   // the timeout gave up on this call and interrupted it
                Thread.currentThread().interrupt();
                return;
            } finally {
                running.decrementAndGet();
            }
            if (bug) {
                throw new IllegalStateException("a bug in the provider client");
            }
            if (failuresLeft.getAndDecrement() > 0 || (succeedEvery > 0 && call % succeedEvery != 0)) {
                throw new PaymentProviderException("simulated refusal");
            }
        }

        private void awaitIgnoringInterrupts() {
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();   // noticed, and ignored
                }
            }
        }
    }
}
