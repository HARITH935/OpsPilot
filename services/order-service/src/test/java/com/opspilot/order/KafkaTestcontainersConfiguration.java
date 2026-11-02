package com.opspilot.order;

import com.opspilot.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
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

    // The topic order-service sends to, as deploy/docker/kafka/topics.txt creates it (Spring's KafkaAdmin does it here)
    @Bean
    NewTopic ordersEventsTopic() {
        return TopicBuilder.name(Topics.ORDERS).partitions(3).replicas(1).build();
    }
}
