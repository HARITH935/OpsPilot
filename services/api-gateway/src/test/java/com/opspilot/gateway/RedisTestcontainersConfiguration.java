package com.opspilot.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

// Import this in tests that start the whole gateway: a real, throwaway Redis (same version as docker-compose), so the
// tests never read or write the Redis in deploy/docker
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4.12-alpine");

    @Bean
    @ServiceConnection(name = "redis")   // Spring Boot points the Redis connection at this container
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }
}
