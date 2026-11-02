package com.opspilot.messaging.outbox;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.EventPayload;
import com.opspilot.events.OrderConfirmed;
import com.opspilot.events.OrderCreated;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.messaging.TestcontainersConfiguration;
import com.opspilot.messaging.TopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// The publisher against a real Postgres (row locks) and a real Kafka (keys, partitions), both in Docker
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxPublisherIntegrationTest {

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaConnectionDetails kafka;

    @BeforeEach
    void emptyTheOutbox() {
        jdbc.sql("DELETE FROM outbox").update();
    }

    @Test
    void sendsEachEventOnceInTheContractFormatWithOneOrdersEventsInOrder() {
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        EventEnvelope aCreated = save(new OrderCreated(orderA, "c-1", List.of(new OrderCreated.Item("SKU-1", 2))));
        EventEnvelope bCreated = save(new OrderCreated(orderB, "c-2", List.of(new OrderCreated.Item("SKU-2", 1))));
        EventEnvelope aConfirmed = save(new OrderConfirmed(orderA));

        assertThat(publisher.publishPending()).isEqualTo(3);
        assertThat(publisher.publishPending()).isZero();   // a sent event is not sent again
        assertThat(unsentRows()).isZero();

        List<ConsumerRecord<String, String>> a = messagesFor(Topics.ORDERS, orderA);
        assertThat(a).extracting(ConsumerRecord::value)   // exactly the JSON EventJson writes, in the order saved
                .containsExactly(EventJson.write(aCreated), EventJson.write(aConfirmed));
        assertThat(a).extracting(ConsumerRecord::partition).containsOnly(a.get(0).partition());   // one partition
        assertThat(messagesFor(Topics.ORDERS, orderB)).extracting(ConsumerRecord::value)
                .containsExactly(EventJson.write(bCreated));
    }

    @Test
    void eachEventGoesToTheTopicOfItsType() {
        UUID order = UUID.randomUUID();
        BigDecimal price = new BigDecimal("89.99");
        EventEnvelope reserved = save(new StockReserved(order, List.of(new StockReserved.Item("SKU-1", 1, price)), price));

        publisher.publishPending();

        assertThat(messagesFor(Topics.INVENTORY, order)).extracting(ConsumerRecord::value)
                .containsExactly(EventJson.write(reserved));
        assertThat(messagesFor(Topics.ORDERS, order)).isEmpty();
    }

    @Test
    void concurrentPublishersSendEveryEventExactlyOnce() throws Exception {
        // 300 events, then 4 publishers at once - like 4 copies of a service polling the same outbox table
        List<UUID> orders = IntStream.range(0, 300).mapToObj(i -> UUID.randomUUID()).toList();
        transactions.executeWithoutResult(tx -> orders.forEach(order -> writer.write(new OrderConfirmed(order))));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(pool.submit(() -> {
                start.await();   // all four start together
                return publisher.publishPending();
            }));
        }
        start.countDown();
        List<Integer> sentPerPublisher = new ArrayList<>();
        for (Future<Integer> run : runs) {
            sentPerPublisher.add(run.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(sentPerPublisher.stream().mapToInt(Integer::intValue).sum()).isEqualTo(300);
        // SKIP LOCKED: the publishers took different rows side by side instead of queueing behind one another
        assertThat(sentPerPublisher).filteredOn(sent -> sent > 0).hasSizeGreaterThanOrEqualTo(2);

        Set<String> keys = orders.stream().map(UUID::toString).collect(Collectors.toSet());
        List<ConsumerRecord<String, String>> received = TopicReader.readAll(kafka.getBootstrapServers(), Topics.ORDERS)
                .stream().filter(message -> keys.contains(message.key())).toList();
        assertThat(received).hasSize(300);
        assertThat(received).extracting(message -> EventJson.read(message.value()).eventId())
                .doesNotHaveDuplicates();
        assertThat(unsentRows()).isZero();
    }

    @Test
    void theProducerUsesTheOutboxSettings(@Autowired ProducerFactory<?, ?> producerFactory,
                                          @Autowired ProducerListener<?, ?> producerListener) {
        assertThat(producerFactory.getConfigurationProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        // Spring Boot gives the KafkaTemplate this one listener: ours, so failures are not logged twice
        assertThat(producerListener).isNotInstanceOf(LoggingProducerListener.class);
    }

    private EventEnvelope save(EventPayload payload) {
        return transactions.execute(tx -> writer.write(payload));   // each in its own transaction, like a service would
    }

    private List<ConsumerRecord<String, String>> messagesFor(String topic, UUID order) {
        // The topic also holds other tests' messages: keep only this order's
        return TopicReader.readAll(kafka.getBootstrapServers(), topic).stream()
                .filter(message -> message.key().equals(order.toString()))
                .toList();
    }

    private long unsentRows() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
}
