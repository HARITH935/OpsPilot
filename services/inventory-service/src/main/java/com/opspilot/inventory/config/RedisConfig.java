package com.opspilot.inventory.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Redis for the product cache. Spring Boot connects with the Lettuce client, using spring.data.redis.* in application.yml.
@Configuration(proxyBeanMethods = false)
class RedisConfig {

    // While Redis is gone, Lettuce keeps reconnecting in the background and would queue every command until the
    // 100 ms timeout. Refusing commands at once instead lets a read go straight to Postgres without that wait
    // (the gateway measured the same setting: 30 requests 4.1 s → 0.4 s). Once Redis is back, commands work again.
    @Bean
    LettuceClientOptionsBuilderCustomizer failFastWhileRedisIsDown() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
