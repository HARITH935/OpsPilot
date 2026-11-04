package com.opspilot.inventory.service;

import com.opspilot.inventory.RedisTestcontainersConfiguration;
import com.opspilot.inventory.config.ProductCacheProperties;
import com.opspilot.inventory.dto.ProductResponse;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The cache on its own against a real Redis (Testcontainers), without Spring: no database, no Kafka
@Testcontainers
class ProductCacheTest {

    private static final String KEY = ProductCache.KEY_PREFIX + "SKU-1";
    // 12.50, not 12.5: the copy must come back exactly as Postgres gave it (BigDecimal equality includes the scale)
    private static final ProductResponse PRODUCT =
            new ProductResponse("SKU-1", "Mechanical Keyboard", new BigDecimal("12.50"), 500, 2, 498);
    private static final ProductCacheProperties ON = new ProductCacheProperties(true, Duration.ofSeconds(60));

    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>(RedisTestcontainersConfiguration.REDIS_IMAGE).withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    private final MeterRegistry meters = new SimpleMeterRegistry();

    @BeforeAll
    static void connect() {
        connections = connectTo(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @BeforeEach
    void emptyRedis() {
        redis.delete(KEY);
    }

    @AfterEach
    void noTransactionLeftOver() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void firstAMissThenTheKeptCopyIsAHit() {
        ProductCache cache = cache(ON);

        assertThat(cache.get("SKU-1")).isEmpty();
        cache.put(PRODUCT);

        assertThat(cache.get("SKU-1")).contains(PRODUCT);
        assertThat(count("result", "miss")).isEqualTo(1);
        assertThat(count("result", "hit")).isEqualTo(1);
    }

    @Test
    void theCountersExistBeforeTheFirstRequest() {
        cache(ON);   // so the dashboard shows 0, not "no data"

        assertThat(count("result", "hit")).isZero();
        assertThat(count("result", "miss")).isZero();
    }

    @Test
    void aCopyIsKeptForTheTtlOnly() {
        cache(new ProductCacheProperties(true, Duration.ofSeconds(60))).put(PRODUCT);

        assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).isBetween(55_000L, 60_000L);
    }

    @Test
    void evictDeletesTheCopy() {
        ProductCache cache = cache(ON);
        cache.put(PRODUCT);

        cache.evict("SKU-1");

        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void evictAfterCommitWaitsForTheCommit() {
        ProductCache cache = cache(ON);
        cache.put(PRODUCT);
        TransactionSynchronizationManager.initSynchronization();   // as if a transaction were running

        cache.evictAfterCommit("SKU-1");
        assertThat(redis.hasKey(KEY)).isTrue();   // not yet: the change isn't committed

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void aRolledBackChangeDeletesNothing() {
        ProductCache cache = cache(ON);
        cache.put(PRODUCT);
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAfterCommit("SKU-1");
        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(redis.hasKey(KEY)).isTrue();   // nothing changed, so the copy is still right
    }

    @Test
    void withoutATransactionTheCopyIsDeletedAtOnce() {
        ProductCache cache = cache(ON);
        cache.put(PRODUCT);

        cache.evictAfterCommit("SKU-1");

        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void switchedOffItNeverTouchesRedis() {
        ProductCache cache = cache(new ProductCacheProperties(false, Duration.ofSeconds(60)));

        cache.put(PRODUCT);
        assertThat(redis.hasKey(KEY)).isFalse();

        redis.opsForValue().set(KEY, "left over from before");
        assertThat(cache.get("SKU-1")).isEmpty();
        cache.evictAfterCommit("SKU-1");
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("left over from before");
        assertThat(count("result", "miss")).isZero();
    }

    @Test
    void anUnreadableCopyIsAMissAndIsDropped() {
        ProductCache cache = cache(ON);
        redis.opsForValue().set(KEY, "{not json");

        assertThat(cache.get("SKU-1")).isEmpty();

        assertThat(count("operation", "read")).isEqualTo(1);
        assertThat(redis.hasKey(KEY)).isFalse();   // so the next read can keep a good copy
    }

    @Test
    void withoutRedisNothingFailsAndEveryErrorIsCounted() throws IOException {
        LettuceConnectionFactory nowhere = connectTo("localhost", freePort());   // nothing listens there
        try {
            ProductCache cache = new ProductCache(new StringRedisTemplate(nowhere), JsonMapper.shared(), ON, meters);

            assertThat(cache.get("SKU-1")).isEmpty();   // no exception: the caller simply reads Postgres
            cache.put(PRODUCT);
            cache.evictAfterCommit("SKU-1");

            assertThat(count("operation", "read")).isEqualTo(1);
            assertThat(count("operation", "write")).isEqualTo(1);
            assertThat(count("operation", "evict")).isEqualTo(1);
            assertThat(count("result", "miss")).isZero();   // an error is not a miss
        } finally {
            nowhere.destroy();
        }
    }

    private ProductCache cache(ProductCacheProperties properties) {
        return new ProductCache(redis, JsonMapper.shared(), properties, meters);
    }

    // hits / misses: count("result", "hit"); errors: count("operation", "read")
    private double count(String tag, String value) {
        String name = tag.equals("result") ? "cache.requests" : "cache.errors";
        var counter = meters.find(name).tag("cache", "products").tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    // The same short timeouts as application.yml, so a missing Redis fails within 100 ms
    private static LettuceConnectionFactory connectTo(String host, int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(100))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(100)).build())
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();   // free a moment ago, and closed again: connections to it are refused
        }
    }
}
