package com.opspilot.messaging;

import com.opspilot.messaging.consumer.ProcessedEvents;
import com.opspilot.messaging.outbox.OutboxPublisher;
import com.opspilot.messaging.outbox.OutboxRepository;
import com.opspilot.messaging.outbox.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.ProducerListener;
import tools.jackson.core.JacksonException;

import java.util.Map;

/**
 * What a service gets by importing this class: the outbox writer and publisher, the duplicate check for consumers,
 * and the Kafka settings both rely on. The service must also turn on scheduling (@EnableScheduling) so the publisher
 * runs, and its database needs the outbox and processed_events tables (every V1 migration creates them).
 */
@Configuration(proxyBeanMethods = false)
@Import({OutboxRepository.class, OutboxWriter.class, OutboxPublisher.class, ProcessedEvents.class})   // as beans
public class MessagingConfiguration {

    static final String DEAD_LETTER_SUFFIX = ".dlq";

    private static final Logger log = LoggerFactory.getLogger(MessagingConfiguration.class);

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

    // Kafka consumer settings for every @KafkaListener of the service
    @Bean
    DefaultKafkaConsumerFactoryCustomizer consumerSettings(Environment environment) {
        String group = environment.getRequiredProperty("spring.application.name");
        return consumerFactory -> consumerFactory.updateConfigs(Map.of(
                // The consumer group: Kafka remembers per group how far it has read each partition (the offset)
                ConsumerConfig.GROUP_ID_CONFIG, group,
                // A group's very first start reads the topic from the beginning, so no event sent before is missed
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    /**
     * What happens when a listener throws: retry the message 3 times (after 0.5 s, 1 s, 2 s), then move it to
     * {@code <topic>.dlq}, same partition, with the error in its headers, and go on with the next message. Spring Boot
     * gives this error handler to every listener.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafka,
                (message, exception) -> new TopicPartition(message.topic() + DEAD_LETTER_SUFFIX, message.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);   // ms; then doubled each time
        backOff.setMultiplier(2);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((message, exception) -> {
            // Spring moves the message silently; one WARN line keeps every dead letter visible in the logs
            log.warn("Moving message {}-{}@{} (key {}) to {}: {}", message.topic(), message.partition(),
                    message.offset(), message.key(), message.topic() + DEAD_LETTER_SUFFIX,
                    NestedExceptionUtils.getMostSpecificCause(exception).getMessage());
            toDeadLetterTopic.accept(message, exception);
        }, backOff);
        // A message that can't be read (bad JSON, unknown event type) won't get better by waiting: dead-letter it now
        errorHandler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return errorHandler;
    }
}
