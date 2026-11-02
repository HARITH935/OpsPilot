package com.opspilot.messaging;

import com.opspilot.events.Topics;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Real, throwaway Postgres and Kafka for the tests, same versions as deploy/docker/docker-compose.yml
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection   // Spring Boot points spring.datasource.* at this container
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));
    }

    @Bean
    @ServiceConnection   // ...and spring.kafka.bootstrap-servers at this one
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");   // like docker-compose: unknown topics fail
    }

    // The topics, as deploy/docker/kafka/topics.txt creates them; Spring's KafkaAdmin creates these when the test starts
    @Bean
    KafkaAdmin.NewTopics eventTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.ORDERS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.INVENTORY).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.PAYMENTS).partitions(3).replicas(1).build());
    }
}
