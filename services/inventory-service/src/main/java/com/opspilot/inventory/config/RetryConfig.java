package com.opspilot.inventory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.retry.RetryException;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.retry.MethodRetryEvent;

import java.lang.reflect.Method;

@Configuration(proxyBeanMethods = false)
@EnableResilientMethods   // switches on @Retryable (built into Spring Framework 7; Spring Boot doesn't enable it)
public class RetryConfig {

    private static final Logger log = LoggerFactory.getLogger(RetryConfig.class);

    // Spring publishes this event for every failed attempt of a @Retryable method, and once more when it gives up.
    // Logging it keeps conflicts visible instead of silently absorbed (they will be searchable in Loki, Phase 6).
    @EventListener
    void logRetryEvent(MethodRetryEvent event) {
        Method method = event.getMethod();
        String name = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        if (event.isRetryAborted()) {
            // Retries used up, or an error that is not retried. Spring wraps the real error in a RetryException.
            Throwable failure = event.getFailure();
            Throwable cause = failure instanceof RetryException && failure.getCause() != null ? failure.getCause() : failure;
            log.warn("{} failed, not retrying: {}", name, cause.toString());
        } else {
            log.info("{} attempt failed: {}", name, event.getFailure().getClass().getSimpleName());
        }
    }
}
