package com.opspilot.gateway.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

// Redis for the rate limiter. Spring Boot connects with the Lettuce client, using spring.data.redis.* in application.yml.
@Configuration(proxyBeanMethods = false)
class RedisConfig {

    // When Redis goes away, Lettuce keeps reconnecting in the background and, by default, queues every command until
    // the connection is back, or until the 100 ms timeout. With Redis shut down normally (docker stop) that made every
    // request 100+ ms slower (measured: 30 requests in 4.1 s). Refusing commands at once while disconnected lets the
    // limiter fail open without the wait. Once Redis is back, commands work again by themselves.
    @Bean
    LettuceClientOptionsBuilderCustomizer failFastWhileRedisIsDown() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    // The Lua script, loaded once. Spring sends it to Redis by its SHA-1 hash (EVALSHA), and the full text only
    // the first time, when Redis doesn't know it yet.
    @Bean
    @SuppressWarnings("rawtypes")   // a Lua list comes back as a plain List (of Longs)
    RedisScript<List> slidingWindowScript() {
        return RedisScript.of(new ClassPathResource("ratelimit/sliding-window.lua"), List.class);
    }
}
