package com.opspilot.gateway.service;

import com.opspilot.gateway.config.RateLimitProperties.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * "May this caller make one more request?" Answered by the Lua script in Redis (ratelimit/sliding-window.lua), which
 * keeps the times of the caller's recent requests and counts those inside the window: a sliding window. Unlike a
 * fixed window (counting per calendar second), a caller can't fit twice the limit around the edge of two windows.
 * Throws a DataAccessException if Redis can't be reached; RateLimitFilter then lets the request through (fail-open).
 */
@Service
public class SlidingWindowRateLimiter {

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public SlidingWindowRateLimiter(StringRedisTemplate redis, RedisScript<List> slidingWindowScript) {
        this.redis = redis;
        this.script = slidingWindowScript;
    }

    public Decision tryAcquire(String key, Limit limit) {
        List<?> result = redis.execute(script, List.of(key), String.valueOf(limit.requests()),
                String.valueOf(limit.window().toMillis()), UUID.randomUUID().toString());
        return new Decision(toLong(result.get(0)) == 1, limit.requests(), toLong(result.get(1)),
                Duration.ofMillis(toLong(result.get(2))));
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();   // Lua numbers come back as Long
    }

    // allowed, the limit, places left in the window, and how long until the oldest counted request leaves it
    public record Decision(boolean allowed, int limit, long remaining, Duration resetIn) {

        // Whole seconds, rounded up (a client waiting 0.3 s too little would be refused again): for the headers
        public long resetInSeconds() {
            return Math.max(1, (resetIn.toMillis() + 999) / 1000);
        }
    }
}
