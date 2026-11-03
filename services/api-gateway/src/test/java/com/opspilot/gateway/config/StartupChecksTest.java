package com.opspilot.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// Settings that must stop the gateway at startup. ApplicationContextRunner starts a tiny Spring context with just
// the two settings records, so each case takes milliseconds instead of a whole application start.
class StartupChecksTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class)
            .withPropertyValues("gateway.jwt.issuer=opspilot-gateway", "gateway.jwt.ttl=30m",
                    "gateway.rate-limit.user.requests=20", "gateway.rate-limit.user.window=1s",
                    "gateway.rate-limit.anonymous.requests=10", "gateway.rate-limit.anonymous.window=1s",
                    "gateway.rate-limit.login.requests=10", "gateway.rate-limit.login.window=1m");

    @Test
    void demoUsersSwitchedOffNeedNoPassword() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void demoUsersSwitchedOnWithoutAPasswordStopTheStart() {
        runner.withPropertyValues("gateway.demo-users.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("DEMO_USERS_PASSWORD"));
    }

    @Test
    void aShortPasswordIsNotEnough() {
        runner.withPropertyValues("gateway.demo-users.enabled=true", "gateway.demo-users.password=admin123456")   // 11
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void demoUsersSwitchedOnWithAProperPasswordStart() {
        runner.withPropertyValues("gateway.demo-users.enabled=true", "gateway.demo-users.password=admin1234567")   // 12
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void onlyOneOfTheTwoKeyFilesStopsTheStart() {
        runner.withPropertyValues("gateway.jwt.private-key-file=/keys/jwt-private.pem")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("Set both"));
    }

    @Test
    void aTokenLifetimeOfZeroStopsTheStart() {
        runner.withPropertyValues("gateway.jwt.ttl=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aRateLimitOfZeroRequestsStopsTheStart() {
        runner.withPropertyValues("gateway.rate-limit.anonymous.requests=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aRateLimitWindowShorterThan100MillisecondsStopsTheStart() {
        runner.withPropertyValues("gateway.rate-limit.user.window=10ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void trustedProxiesAreReadAsAList() {
        runner.withPropertyValues("gateway.rate-limit.trusted-proxies=10.0.0.0/8, 192.168.1.1")
                .run(context -> assertThat(context.getBean(RateLimitProperties.class).trustedProxies())
                        .containsExactly("10.0.0.0/8", "192.168.1.1"));
        runner.withPropertyValues("gateway.rate-limit.trusted-proxies=")   // what GATEWAY_TRUSTED_PROXIES unset gives
                .run(context -> assertThat(context.getBean(RateLimitProperties.class).trustedProxies()).isEmpty());
    }

    @EnableConfigurationProperties({JwtProperties.class, DemoUsersProperties.class, RateLimitProperties.class})
    static class Settings {
    }
}
