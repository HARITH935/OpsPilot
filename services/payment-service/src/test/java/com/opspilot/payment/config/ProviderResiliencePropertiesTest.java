package com.opspilot.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// The protection settings around the payment provider: application.yml's values bind, wrong ones stop the start
class ProviderResiliencePropertiesTest {

    private static final String P = "payment.provider.resilience.";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class)
            .withPropertyValues(P + "timeout=2s",
                    P + "retry.max-retries=2", P + "retry.first-wait=200ms", P + "retry.multiplier=2",
                    P + "retry.jitter=0.5",
                    P + "circuit-breaker.failure-rate-threshold=50", P + "circuit-breaker.sliding-window-size=20",
                    P + "circuit-breaker.minimum-number-of-calls=20", P + "circuit-breaker.wait-in-open-state=30s",
                    P + "circuit-breaker.permitted-calls-in-half-open-state=3",
                    P + "bulkhead.max-concurrent-calls=5", P + "bulkhead.max-wait=1s");

    @Test
    void theValuesFromApplicationYmlAreBound() {
        contextRunner.run(context -> {
            ProviderResilienceProperties properties = context.getBean(ProviderResilienceProperties.class);
            assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.retry().maxRetries()).isEqualTo(2);
            assertThat(properties.circuitBreaker().waitInOpenState()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.bulkhead().maxConcurrentCalls()).isEqualTo(5);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "timeout=0s",                                   // every call would time out
            "retry.jitter=1.5",                             // a wait can't be more than 100% shorter
            "retry.max-retries=11",                         // hammering a refusing provider
            "circuit-breaker.failure-rate-threshold=0",     // "open when 0% failed" = always open
            "bulkhead.max-concurrent-calls=0",              // no call could ever run
            "bulkhead.max-wait=-1s"                         // waiting a negative time makes no sense
    })
    void wrongValuesStopTheStartup(String setting) {
        contextRunner.withPropertyValues(P + setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("payment.provider.resilience");
        });
    }

    @EnableConfigurationProperties(ProviderResilienceProperties.class)
    static class EnableProperties {
    }
}
