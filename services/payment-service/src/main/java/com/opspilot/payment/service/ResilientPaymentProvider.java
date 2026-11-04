package com.opspilot.payment.service;

import com.opspilot.payment.config.ProviderResilienceProperties;
import com.opspilot.payment.exception.PaymentProviderException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

/**
 * Wraps the real payment provider (a decorator: same interface, extra behaviour), so PaymentService doesn't change.
 * Every charge and refund goes through, outside in (architecture doc B2.4):
 * <pre>CircuitBreaker ( Retry ( TimeLimiter ( Bulkhead ( the provider ) ) ) )</pre>
 * <ul>
 *   <li><b>Bulkhead:</b> at most N provider calls at once. Innermost, so it counts the calls that really run, also
 *       one the timeout already gave up on: a hanging provider can't tie up more than N threads.</li>
 *   <li><b>TimeLimiter:</b> no answer in time → PROVIDER_TIMEOUT. Not retried: with a real provider a timeout means
 *       "unknown", the money may have been taken, so the next step is asking the provider, not charging again.</li>
 *   <li><b>Retry:</b> only after PROVIDER_ERROR ("refused, nothing was taken"): trying again can't charge twice.</li>
 *   <li><b>CircuitBreaker:</b> after too many failed payments the provider isn't called for a while →
 *       PROVIDER_UNAVAILABLE at once, so the saga cancels the order quickly instead of every payment waiting for a
 *       broken provider. It sits outside the retries, so it judges payments, not attempts: a payment that worked on
 *       its second try is a success. (Measured with attempts counted, at 50% provider errors = chaos C12, the breaker
 *       opened at once and only 6 resp. 21 of 36 payments went through; judged per payment it stays closed.)</li>
 * </ul>
 * Charges and refunds share one set: it is one provider. Metrics and log lines carry the name "payment-provider".
 */
public class ResilientPaymentProvider implements PaymentProvider {

    public static final String NAME = "payment-provider";
    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentProvider.class);

    private final PaymentProvider provider;
    private final ExecutorService calls;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientPaymentProvider(PaymentProvider provider, ProviderResilienceProperties properties,
                                    ExecutorService calls, MeterRegistry meters) {
        this.provider = provider;
        this.calls = calls;

        ProviderResilienceProperties.Bulkhead limits = properties.bulkhead();
        BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(limits.maxConcurrentCalls())
                .maxWaitDuration(limits.maxWait())
                .build());
        this.bulkhead = bulkheads.bulkhead(NAME);

        TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(properties.timeout())
                .cancelRunningFuture(true)   // time is up: interrupt the call instead of letting it run on unseen
                .build());
        this.timeLimiter = timeLimiters.timeLimiter(NAME);

        ProviderResilienceProperties.Breaker breaker = properties.circuitBreaker();
        CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)   // "the last N calls"
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .waitDurationInOpenState(breaker.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)   // HALF_OPEN on time, even with no payments
                // A full bulkhead is our own limit, not a sign the provider is broken
                .ignoreExceptions(BulkheadFullException.class)
                .build());
        this.circuitBreaker = circuitBreakers.circuitBreaker(NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
            if (transition.getToState() == CircuitBreaker.State.OPEN) {
                log.warn("Circuit breaker {}: {} → OPEN, the provider isn't called for now ({}% of recent calls failed)",
                        NAME, transition.getFromState(), Math.round(circuitBreaker.getMetrics().getFailureRate()));
            } else {
                log.info("Circuit breaker {}: {} → {}", NAME, transition.getFromState(), transition.getToState());
            }
        });

        ProviderResilienceProperties.Retries retries = properties.retry();
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1 + retries.maxRetries())   // the first try plus the retries
                // 200 ms, 400 ms, each ± 50% at random: payments that failed together don't retry together
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        retries.firstWait(), retries.multiplier(), retries.jitter()))
                // Only "the provider said no". Not a timeout (unknown outcome), not a full bulkhead.
                .retryOnException(e -> e instanceof PaymentProviderException failure
                        && PaymentProviderException.ERROR.equals(failure.reason()))
                .build());
        this.retry = retryRegistry.retry(NAME);
        retry.getEventPublisher().onRetry(event -> log.info("Payment provider: retry {} in {} ms ({})",
                event.getNumberOfRetryAttempts(), event.getWaitInterval().toMillis(),
                event.getLastThrowable() == null ? "-" : event.getLastThrowable().getMessage()));

        // resilience4j_bulkhead_*, _timelimiter_calls_total, _circuitbreaker_state, _retry_calls_total, all name="payment-provider"
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meters);
        TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timeLimiters).bindTo(meters);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meters);
    }

    @Override
    public void charge(UUID orderId, BigDecimal amount) {
        call(() -> provider.charge(orderId, amount));
    }

    @Override
    public void refund(UUID orderId, BigDecimal amount) {
        call(() -> provider.refund(orderId, amount));
    }

    CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private void call(Runnable providerCall) {
        Callable<Void> limited = () -> timeLimiter.executeFutureSupplier(
                // The call runs on its own virtual thread, so this thread can stop waiting once the time is up
                () -> calls.submit(Bulkhead.decorateCallable(bulkhead, () -> {
                    providerCall.run();
                    return null;
                })));
        try {
            CircuitBreaker.decorateCallable(circuitBreaker, Retry.decorateCallable(retry, limited)).call();
        } catch (PaymentProviderException e) {
            throw e;   // the provider said no, also after the retries
        } catch (TimeoutException e) {
            throw new PaymentProviderException(PaymentProviderException.TIMEOUT, "No answer from the payment provider"
                    + " within " + timeLimiter.getTimeLimiterConfig().getTimeoutDuration().toMillis() + " ms", e);
        } catch (CallNotPermittedException e) {
            throw new PaymentProviderException(PaymentProviderException.UNAVAILABLE,
                    "Payment provider not called: it failed too often just now (circuit open)", e);
        } catch (BulkheadFullException e) {
            throw new PaymentProviderException(PaymentProviderException.UNAVAILABLE,
                    "Payment provider not called: too many calls to it already running", e);
        } catch (RuntimeException e) {
            throw e;   // a bug, not an answer from the provider: up to the Kafka error handler (retries, then the DLQ)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // e.g. shutting down: no FAILED payment, the event is read again later
            throw new IllegalStateException("Interrupted while calling the payment provider", e);
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected failure calling the payment provider", e);
        }
    }
}
