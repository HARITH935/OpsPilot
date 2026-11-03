package com.opspilot.gateway.service;

import com.opspilot.gateway.RedisTestcontainersConfiguration;
import com.opspilot.gateway.config.RateLimitProperties.Limit;
import com.opspilot.gateway.service.SlidingWindowRateLimiter.Decision;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The Lua script itself, against a real Redis in a throwaway container. No Spring application: just a Redis
// connection and the limiter, so each case is quick.
@Testcontainers   // starts the @Container field before the tests and stops it afterwards
class SlidingWindowRateLimiterTest {

    @Container
    private static final GenericContainer<?> redisContainer =
            new GenericContainer<>(RedisTestcontainersConfiguration.REDIS_IMAGE).withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static SlidingWindowRateLimiter limiter;

    @BeforeAll
    @SuppressWarnings("rawtypes")
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        RedisScript<List> script = RedisScript.of(new ClassPathResource("ratelimit/sliding-window.lua"), List.class);
        limiter = new SlidingWindowRateLimiter(redis, script);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @BeforeEach
    void emptyRedis() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void upToTheLimitIsAllowedThenRefused() {
        Limit threePerSecond = new Limit(3, Duration.ofSeconds(1));

        List<Decision> decisions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            decisions.add(limiter.tryAcquire("ratelimit:test:ip:a", threePerSecond));
        }

        assertThat(decisions).extracting(Decision::allowed).containsExactly(true, true, true, false);
        assertThat(decisions).extracting(Decision::remaining).containsExactly(2L, 1L, 0L, 0L);
        assertThat(decisions.getLast().resetIn()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aPlaceFreesExactlyWhenTheOldestRequestLeavesTheWindow() throws Exception {
        Limit twoPerSecond = new Limit(2, Duration.ofSeconds(1));
        limiter.tryAcquire("ratelimit:test:ip:b", twoPerSecond);
        Thread.sleep(400);
        limiter.tryAcquire("ratelimit:test:ip:b", twoPerSecond);
        Decision refused = limiter.tryAcquire("ratelimit:test:ip:b", twoPerSecond);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.resetIn()).isLessThanOrEqualTo(Duration.ofMillis(600));   // the first one leaves first

        Thread.sleep(refused.resetIn().toMillis() + 50);   // the first request has left; the second is still inside

        assertThat(limiter.tryAcquire("ratelimit:test:ip:b", twoPerSecond).allowed()).isTrue();
        assertThat(limiter.tryAcquire("ratelimit:test:ip:b", twoPerSecond).allowed()).isFalse();   // full again
    }

    @Test
    void aRefusedRequestIsNotCounted() {
        Limit onePerSecond = new Limit(1, Duration.ofSeconds(1));

        limiter.tryAcquire("ratelimit:test:ip:c", onePerSecond);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("ratelimit:test:ip:c", onePerSecond);   // refused: costs nothing
        }

        assertThat(redis.opsForZSet().zCard("ratelimit:test:ip:c")).isEqualTo(1);
    }

    @Test
    void eachKeyHasItsOwnBudget() {
        Limit onePerSecond = new Limit(1, Duration.ofSeconds(1));

        assertThat(limiter.tryAcquire("ratelimit:orders:ip:d", onePerSecond).allowed()).isTrue();
        assertThat(limiter.tryAcquire("ratelimit:orders:ip:d", onePerSecond).allowed()).isFalse();
        assertThat(limiter.tryAcquire("ratelimit:orders:ip:e", onePerSecond).allowed()).isTrue();      // another caller
        assertThat(limiter.tryAcquire("ratelimit:products:ip:d", onePerSecond).allowed()).isTrue();    // another route
    }

    @Test
    void theCounterRemovesItselfAfterOneWindow() {
        limiter.tryAcquire("ratelimit:test:ip:f", new Limit(5, Duration.ofSeconds(2)));

        Long millisToLive = redis.getExpire("ratelimit:test:ip:f", TimeUnit.MILLISECONDS);
        assertThat(millisToLive).isPositive().isLessThanOrEqualTo(2000);   // nothing piles up in Redis
    }

    @Test
    void fiftyRequestsAtTheSameMomentLetExactlyTheLimitThrough() throws Exception {
        Limit twentyPerTenSeconds = new Limit(20, Duration.ofSeconds(10));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> answers = new ArrayList<>();

        try (ExecutorService threads = Executors.newFixedThreadPool(50)) {
            for (int i = 0; i < 50; i++) {
                answers.add(threads.submit(() -> {
                    start.await();   // all 50 wait here, then go together
                    return limiter.tryAcquire("ratelimit:test:ip:g", twentyPerTenSeconds).allowed();
                }));
            }
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> answer : answers) {
                allowed += answer.get() ? 1 : 0;
            }
            // The script counts and adds in one step, so no two requests can both take the last place
            assertThat(allowed).isEqualTo(20);
        }
    }
}
