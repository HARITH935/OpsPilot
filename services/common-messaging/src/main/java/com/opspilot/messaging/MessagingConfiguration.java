package com.opspilot.messaging;

import com.opspilot.messaging.outbox.OutboxPublisher;
import com.opspilot.messaging.outbox.OutboxRepository;
import com.opspilot.messaging.outbox.OutboxWriter;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.ProducerListener;

import java.util.Map;

/**
 * What a service gets by importing this class: the outbox writer and publisher. The service must also turn on
 * scheduling (@EnableScheduling) so the publisher runs, and its database needs the outbox table (every V1 migration
 * creates it).
 */
@Configuration(proxyBeanMethods = false)
@Import({OutboxRepository.class, OutboxWriter.class, OutboxPublisher.class})   // registers them as Spring beans
public class MessagingConfiguration {

    // Kafka producer settings the publisher relies on, applied on top of the service's spring.kafka.* properties
    @Bean
    DefaultKafkaProducerFactoryCustomizer outboxProducerSettings() {
        return producerFactory -> producerFactory.updateConfigs(Map.of(
                ProducerConfig.ACKS_CONFIG, "all",                     // "sent" = stored by Kafka, not just received
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,             // Kafka unreachable: give up after 5 s (default 60 s)
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,       // wait at most 5 s for one reply (default 30 s)
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000));   // success or failure within 10 s (default 2 min)
    }

    // The publisher logs a failed send itself, in one WARN line. This silent listener replaces Spring's default one,
    // which would log the same failure again as an ERROR, with the event's content in it.
    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {
        return new ProducerListener<>() {   // every method of ProducerListener does nothing by default
        };
    }
}
