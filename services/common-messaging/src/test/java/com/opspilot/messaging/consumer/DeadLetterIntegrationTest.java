package com.opspilot.messaging.consumer;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderCancelled;
import com.opspilot.events.OrderConfirmed;
import com.opspilot.messaging.TestListener;
import com.opspilot.messaging.TestcontainersConfiguration;
import com.opspilot.messaging.TopicReader;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// The shared retry + dead-letter rules (MessagingConfiguration.kafkaErrorHandler), with TestListener on a real Kafka
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterIntegrationTest {

    private static final String DEAD_LETTER_TOPIC = TestListener.TOPIC + ".dlq";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private TestListener listener;

    @Autowired
    private KafkaConnectionDetails connection;

    @Test
    void aMessageThatKeepsFailingIsRetriedThreeTimesThenDeadLettered() throws Exception {
        UUID order = UUID.randomUUID();
        String event = EventJson.write(EventEnvelope.of(new OrderCancelled(order, "TEST"), null));
        RecordMetadata sent = send(order.toString(), event);

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(order.toString());

        assertThat(listener.deliveriesOf(order.toString())).isEqualTo(4);   // 1 delivery + 3 retries
        assertThat(deadLetter.value()).isEqualTo(event);                    // the message itself, unchanged
        assertThat(deadLetter.partition()).isEqualTo(sent.partition());     // same partition as the original
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Simulated failure");
        assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(TestListener.TOPIC);
    }

    @Test
    void anUnreadableMessageIsDeadLetteredWithoutRetries() throws Exception {
        String key = "garbage-" + UUID.randomUUID();
        send(key, "this is not an event");

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(key);

        assertThat(listener.deliveriesOf(key)).isEqualTo(1);   // retrying can't fix it, so it wasn't retried
        assertThat(deadLetter.value()).isEqualTo("this is not an event");
    }

    @Test
    void aGoodMessageIsHandledOnceAndNotDeadLettered() throws Exception {
        UUID order = UUID.randomUUID();
        send(order.toString(), EventJson.write(EventEnvelope.of(new OrderConfirmed(order), null)));

        await().atMost(Duration.ofSeconds(30)).until(() -> listener.deliveriesOf(order.toString()) == 1);
        assertThat(deadLettersFor(order.toString())).isEmpty();
    }

    @Test
    void consumersUseTheServiceNameAsGroupAndStartFromTheBeginning(@Autowired ConsumerFactory<?, ?> consumerFactory) {
        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "messaging-test")   // spring.application.name
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    private RecordMetadata send(String key, String value) throws Exception {
        return kafka.send(TestListener.TOPIC, key, value).get().getRecordMetadata();
    }

    // The first start of the listener's consumer group takes a few seconds, then retries add 0.5 + 1 + 2 s
    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> !deadLettersFor(key).isEmpty());
        List<ConsumerRecord<String, String>> deadLetters = deadLettersFor(key);
        assertThat(deadLetters).hasSize(1);
        return deadLetters.get(0);
    }

    private List<ConsumerRecord<String, String>> deadLettersFor(String key) {
        return TopicReader.readAll(connection.getBootstrapServers(), DEAD_LETTER_TOPIC).stream()
                .filter(message -> key.equals(message.key()))
                .toList();
    }

    private static String header(ConsumerRecord<String, String> message, String name) {
        return new String(message.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
