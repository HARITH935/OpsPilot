package com.opspilot.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// Starts a tiny Spring context holding only these settings, to check what happens with good and bad values
class PaymentProviderPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class);

    @Test
    void validValuesAreBound() {
        contextRunner
                .withPropertyValues("payment.provider.failure-rate=0.5", "payment.provider.latency=200ms")
                .run(context -> {
                    PaymentProviderProperties properties = context.getBean(PaymentProviderProperties.class);
                    assertThat(properties.failureRate()).isEqualTo(0.5);
                    assertThat(properties.latency()).isEqualTo(Duration.ofMillis(200));
                });
    }

    @ParameterizedTest
    @CsvSource({
            "1.5,  100ms",   // more than 100% of charges can't fail
            "-0.1, 100ms",   // a negative share makes no sense
            "0.0,  -5ms",    // waiting a negative time makes no sense
            "lots, 100ms"    // not a number at all
    })
    void invalidValuesStopTheStartup(String failureRate, String latency) {
        contextRunner
                .withPropertyValues("payment.provider.failure-rate=" + failureRate, "payment.provider.latency=" + latency)
                .run(context -> {
                    assertThat(context).hasFailed();   // the service refuses to start
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("payment.provider");
                });
    }

    @EnableConfigurationProperties(PaymentProviderProperties.class)
    static class EnableProperties {
    }
}
