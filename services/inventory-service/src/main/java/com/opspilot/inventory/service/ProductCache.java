package com.opspilot.inventory.service;

import com.opspilot.inventory.config.ProductCacheProperties;
import com.opspilot.inventory.dto.ProductResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A copy of each product in Redis, so GET /api/products/{sku} can skip Postgres (cache-aside, architecture doc B2.4):
 * ProductService looks here first; on a miss it reads Postgres and puts the answer here for the TTL (60 s).
 * A copy is deleted as soon as a change to its product is committed (ProductCacheEvictor).
 *
 * <p>Fail-open: if Redis can't be reached (or holds something unreadable), the read goes to Postgres. The shop keeps
 * working, a little slower, and the problem shows in the log (a WARN at most every 30 s) and in cache_errors_total.
 *
 * <p>Known limit: a read that misses, then loses the race to a change (the change commits and deletes the copy
 * before the read puts its older answer), leaves an out-of-date copy for at most the TTL.
 */
@Service
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);

    // "v1": if ProductResponse ever changes shape, the next version reads new keys and never the old copies
    static final String KEY_PREFIX = "inventory:product:v1:";
    private static final String CACHE = "products";
    private static final Duration WARN_EVERY = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final ProductCacheProperties properties;
    private final Counter hits;
    private final Counter misses;
    private final MeterRegistry meters;
    private final AtomicBoolean redisDown = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.EPOCH);

    public ProductCache(StringRedisTemplate redis, JsonMapper json, ProductCacheProperties properties,
                        MeterRegistry meters) {
        this.redis = redis;
        this.json = json;   // the same JSON settings the API uses to write its answers
        this.properties = properties;
        this.meters = meters;
        // Registered now, so they show 0 (not "missing") from the start. Prometheus: cache_requests_total{cache,result}
        this.hits = requests("hit");
        this.misses = requests("miss");
    }

    /** The cached copy, or empty: not cached yet, cache switched off, or Redis not usable (then Postgres answers). */
    public Optional<ProductResponse> get(String sku) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            String cached = redis.opsForValue().get(KEY_PREFIX + sku);
            redisReachable();
            if (cached == null) {
                misses.increment();
                return Optional.empty();
            }
            ProductResponse product = json.readValue(cached, ProductResponse.class);
            hits.increment();
            return Optional.of(product);
        } catch (JacksonException e) {
            failed("read", sku, e);
            evict(sku);   // a copy that can't be read is useless: drop it, so the next read caches a fresh one
            return Optional.empty();
        } catch (DataAccessException e) {
            failed("read", sku, e);
            return Optional.empty();
        }
    }

    /** Keeps a copy for the TTL. Never fails the request: without Redis, the next read simply asks Postgres again. */
    public void put(ProductResponse product) {
        if (!properties.enabled()) {
            return;
        }
        try {
            // SET with an expiry in one command, so a copy can never stay forever
            redis.opsForValue().set(KEY_PREFIX + product.sku(), json.writeValueAsString(product), properties.ttl());
            redisReachable();
        } catch (DataAccessException | JacksonException e) {
            failed("write", product.sku(), e);
        }
    }

    /**
     * Deletes the copy once the current transaction has committed. Not earlier: until the commit, the database
     * still shows the old values, and a read in between would put the old values right back into the cache.
     * Nothing is deleted if the transaction rolls back, because then nothing changed.
     */
    public void evictAfterCommit(String sku) {
        if (!properties.enabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(sku);   // no transaction around this change (not expected): delete right away
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(sku);
            }
        });
    }

    void evict(String sku) {
        try {
            redis.delete(KEY_PREFIX + sku);
            redisReachable();
        } catch (DataAccessException e) {
            failed("evict", sku, e);   // the copy can stay out of date for at most the TTL
        }
    }

    private Counter requests(String result) {
        return Counter.builder("cache.requests")
                .description("Product cache lookups, by result")
                .tag("cache", CACHE)
                .tag("result", result)
                .register(meters);
    }

    private void failed(String operation, String sku, Exception e) {
        Counter.builder("cache.errors")
                .description("Product cache operations that failed (Postgres answered instead)")
                .tag("cache", CACHE)
                .tag("operation", operation)
                .register(meters)   // the same counter is returned every time for the same name and tags
                .increment();
        if (e instanceof JacksonException unreadable) {
            log.warn("Cached copy of {} can't be read, Postgres answers instead: {}", sku, unreadable.getOriginalMessage());
            return;
        }
        redisDown.set(true);
        Instant now = Instant.now();
        Instant last = lastWarning.get();
        if (now.isAfter(last.plus(WARN_EVERY)) && lastWarning.compareAndSet(last, now)) {   // one thread warns
            log.warn("Redis can't be reached, so products are read from Postgres until it is back (fail-open): {}",
                    ((DataAccessException) e).getMostSpecificCause().toString());
        }
    }

    private void redisReachable() {
        if (redisDown.compareAndSet(true, false)) {
            log.info("Redis is reachable again: the product cache is back on");
        }
    }
}
