package com.opspilot.gateway.config;

import com.opspilot.gateway.exception.GatewayExceptionHandler;
import com.opspilot.gateway.filter.ResilienceFilterFunctions;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.filter.FilterSupplier;
import org.springframework.cloud.gateway.server.mvc.filter.SimpleFilterSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The Resilience4j instances behind each route (RouteResilience), their metrics, and their log lines
@Configuration(proxyBeanMethods = false)
class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    // A service's own 5xx answer counts as a failure for its circuit breaker. A 4xx doesn't: that's the caller's mistake.
    private static final Set<Integer> SERVICE_FAILURES = Set.of(500, 502, 503, 504);
    // Answers worth retrying: "I can't take it right now" (e.g. restarting), not "I tried and failed" (500)
    private static final Set<Integer> TRY_AGAIN = Set.of(502, 503);

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties properties, MeterRegistry meters) {
        ResilienceProperties.Breaker breaker = properties.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)   // "the last N calls"
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .waitDurationInOpenState(breaker.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.permittedCallsInHalfOpenState())
                // Move to HALF_OPEN when the wait is over, even with no traffic: the state metric stays true
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Exceptions (no answer, too slow) are failures anyway; this adds the 5xx answers
                .recordResult(result -> result instanceof ServerResponse response
                        && SERVICE_FAILURES.contains(response.statusCode().value()))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.getEventPublisher().onEntryAdded(added -> logStateChanges(added.getAddedEntry()));
        // resilience4j_circuitbreaker_state, _calls_seconds (successful / failed), _not_permitted_calls_total, ...
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    TimeLimiterRegistry timeLimiterRegistry(ResilienceProperties properties, MeterRegistry meters) {
        // The default; RouteResilience gives every route its own (gateway.resilience.timeouts)
        TimeLimiterRegistry registry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(properties.timeout())
                .cancelRunningFuture(true)
                .build());
        TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(registry).bindTo(meters);   // resilience4j_timelimiter_calls_total
        return registry;
    }

    @Bean
    RetryRegistry retryRegistry(ResilienceProperties properties, MeterRegistry meters) {
        ResilienceProperties.Retries retries = properties.retry();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(1 + retries.maxRetries())   // the first try plus the retries
                // 100 ms, 200 ms, 400 ms, each ± 50% at random: clients that failed together don't retry together
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        retries.firstWait(), retries.multiplier(), retries.jitter()))
                // Only "couldn't reach the service". Not a timeout: the request may still be running there, and a
                // second one would only add load. Not an open circuit (CallNotPermittedException): that means "stop".
                .retryOnException(e -> e instanceof ResourceAccessException
                        && !GatewayExceptionHandler.answeredTooSlowly(e))
                .retryOnResult(result -> result instanceof ServerResponse response
                        && TRY_AGAIN.contains(response.statusCode().value()))
                .build();   // after the last attempt, its answer (or error) is passed on as it is
        RetryRegistry registry = RetryRegistry.of(config);
        registry.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher().onRetry(retry ->
                log.info("Route {}: retry {} in {} ms ({})", retry.getName(), retry.getNumberOfRetryAttempts(),
                        retry.getWaitInterval().toMillis(), retry.getLastThrowable() == null
                                ? "the service answered 502 / 503"
                                : retry.getLastThrowable().getClass().getSimpleName())));
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meters);   // resilience4j_retry_calls_total{kind}
        return registry;
    }

    // Each call to a service runs on a virtual thread of its own (see RouteResilience): cheap, one per call
    @Bean(destroyMethod = "close")
    ExecutorService routeCalls() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // Makes the static methods of ResilienceFilterFunctions usable as filters in application.yml
    @Bean
    FilterSupplier resilienceFilterSupplier() {
        return new SimpleFilterSupplier(ResilienceFilterFunctions.class);
    }

    private static void logStateChanges(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            switch (event.getStateTransition().getToState()) {
                case OPEN -> log.warn("Circuit breaker {}: {} → OPEN, calls are refused for now ({}% of recent calls failed)",
                        event.getCircuitBreakerName(), event.getStateTransition().getFromState(),
                        Math.round(circuitBreaker.getMetrics().getFailureRate()));
                case HALF_OPEN -> log.info("Circuit breaker {}: OPEN → HALF_OPEN, letting a few test calls through",
                        event.getCircuitBreakerName());
                default -> log.info("Circuit breaker {}: {} → {}", event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(), event.getStateTransition().getToState());
            }
        });
    }
}
