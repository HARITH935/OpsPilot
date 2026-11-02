package com.opspilot.order;

import com.opspilot.events.Topics;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// Import this, with TestcontainersConfiguration, in tests that start the whole app: a real, throwaway Kafka
// (same version as docker-compose), so the outbox publisher never sends test events to the Kafka in deploy/docker
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    @Bean
    @ServiceConnection   // Spring Boot points spring.kafka.bootstrap-servers at this container
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");   // like docker-compose: unknown topics fail
    }

    // The topics order-service sends to and reads, as deploy/docker/kafka/topics.txt creates them
    // (Spring's KafkaAdmin creates these when the test starts)
    @Bean
    KafkaAdmin.NewTopics orderTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.ORDERS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.INVENTORY).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.INVENTORY + ".dlq").partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.PAYMENTS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.PAYMENTS + ".dlq").partitions(3).replicas(1).build());
    }
}
