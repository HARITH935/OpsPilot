package com.opspilot.inventory;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderCancelled;
import com.opspilot.events.OrderCreated;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.StockRejected;
import com.opspilot.events.StockReleased;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Inventory's saga step, end to end: OrderCreated on orders.events -> listener -> reservation + outbox -> publisher
// -> StockReserved / StockRejected on inventory.events; OrderCancelled -> release (or return) -> StockReleased.
// Kafka and Postgres are real (Testcontainers).
@SpringBootTest
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class OrderEventsIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void orderCreatedHoldsTheStockAndIsAnsweredWithStockReserved() throws Exception {
        String sku = newProduct("12.50", 10);
        UUID order = UUID.randomUUID();

        send(orderCreated(order, sku, 2));

        StockReserved reply = (StockReserved) awaitReply(order);
        assertThat(reply).isEqualTo(new StockReserved(order,
                List.of(new StockReserved.Item(sku, 2, new BigDecimal("12.50"))), new BigDecimal("25.00")));
        assertThat(reservedUnits(sku)).isEqualTo(2);
        assertThat(reservationRows(order)).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceIsHandledOnce() throws Exception {
        String sku = newProduct("5.00", 10);
        UUID order = UUID.randomUUID();
        String event = orderCreated(order, sku, 3);

        send(event);
        RecordMetadata duplicate = send(event);   // the same event id again, as an at-least-once redelivery would
        awaitConsumed(duplicate);

        assertThat(reservedUnits(sku)).isEqualTo(3);   // not 6
        assertThat(reservationRows(order)).isEqualTo(1);
        awaitReply(order);
        assertThat(replies(order)).hasSize(1);   // one StockReserved, not two
        assertThat(deadLetters(order)).isEmpty();   // the duplicate was skipped, not failed and parked
    }

    @Test
    void notEnoughStockIsAnsweredWithStockRejected() throws Exception {
        String sku = newProduct("24.99", 3);   // like the limited desk mat
        UUID order = UUID.randomUUID();

        send(orderCreated(order, sku, 5));

        assertThat(awaitReply(order)).isEqualTo(new StockRejected(order, sku, "OUT_OF_STOCK"));
        assertThat(reservedUnits(sku)).isZero();
        assertThat(reservationRows(order)).isZero();
    }

    @Test
    void orderCancelledReleasesTheHeldStockAndIsAnsweredWithStockReleased() throws Exception {
        String sku = newProduct("8.00", 10);
        UUID order = UUID.randomUUID();
        send(orderCreated(order, sku, 4));
        awaitReply(order);   // StockReserved: 4 units held

        send(orderCancelled(order, "TIMEOUT"));   // what order-service sends when the saga times out

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> replies(order).size() == 2);
        assertThat(replies(order).get(1)).isEqualTo(new StockReleased(order));
        assertThat(reservedUnits(sku)).isZero();   // back on sale
        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
    }

    @Test
    void orderCancelledAfterALatePaymentReturnsTheCommittedStock() throws Exception {   // ADR 0006
        String sku = newProduct("15.00", 10);
        UUID order = UUID.randomUUID();
        send(orderCreated(order, sku, 2));
        awaitReply(order);   // StockReserved: 2 units held

        // The order was paid only after the saga timeout, and that PaymentCompleted got here before the OrderCancelled
        kafka.send(Topics.PAYMENTS, order.toString(), EventJson.write(EventEnvelope.of(
                new PaymentCompleted(order, UUID.randomUUID(), new BigDecimal("30.00")), null))).get();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> "COMMITTED".equals(reservationStatus(order)));
        assertThat(stock(sku)).isEqualTo(8);   // the 2 units left the warehouse

        send(orderCancelled(order, "TIMEOUT"));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> replies(order).size() == 2);
        assertThat(replies(order).get(1)).isEqualTo(new StockReleased(order));
        assertThat(reservationStatus(order)).isEqualTo("RETURNED");
        assertThat(stock(sku)).isEqualTo(10);   // back in the warehouse...
        assertThat(reservedUnits(sku)).isZero();   // ...and on sale
    }

    @Test
    void orderCancelledWithNothingHeldReleasesNothing() throws Exception {
        String sku = newProduct("24.99", 3);
        UUID order = UUID.randomUUID();
        send(orderCreated(order, sku, 5));
        awaitReply(order);   // StockRejected: nothing held

        awaitConsumed(send(orderCancelled(order, "OUT_OF_STOCK")));   // order cancels it because of that rejection

        assertThat(outboxEvents(order)).containsExactly("StockRejected");   // no StockReleased written
        assertThat(deadLetters(order)).isEmpty();
    }

    @Test
    void aMessageThatIsNotAnEventIsDeadLetteredAndTheNextOneStillGetsThrough() throws Exception {
        String sku = newProduct("1.00", 10);
        UUID order = UUID.randomUUID();

        send(order.toString(), "not an event");   // same key, so the same partition as the good event behind it
        send(orderCreated(order, sku, 1));

        awaitReply(order);   // the bad message did not block the partition
        assertThat(deadLetters(order)).extracting(ConsumerRecord::value).containsExactly("not an event");
    }

    private List<ConsumerRecord<String, String>> deadLetters(UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.ORDERS + ".dlq").stream()
                .filter(message -> order.toString().equals(message.key()))
                .toList();
    }

    private String newProduct(String price, int stock) {
        String sku = "SKU-T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        productRepository.save(new Product(sku, "Test product", new BigDecimal(price), stock));
        return sku;
    }

    private static String orderCreated(UUID order, String sku, int qty) {
        return EventJson.write(EventEnvelope.of(new OrderCreated(order, "c-1", List.of(new OrderCreated.Item(sku, qty))), null));
    }

    private static String orderCancelled(UUID order, String reason) {
        return EventJson.write(EventEnvelope.of(new OrderCancelled(order, reason), null));
    }

    private RecordMetadata send(String event) throws Exception {
        return send(EventJson.read(event).aggregateId().toString(), event);
    }

    private RecordMetadata send(String key, String value) throws Exception {
        return kafka.send(Topics.ORDERS, key, value).get().getRecordMetadata();
    }

    // The listener's first start takes a few seconds (the consumer group forms), then the publisher adds ~0.5 s
    private Object awaitReply(UUID order) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> !replies(order).isEmpty());
        return replies(order).get(0);
    }

    private List<Object> replies(UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.INVENTORY).stream()
                .filter(message -> order.toString().equals(message.key()))
                .map(message -> (Object) EventJson.read(message.value()).payload())
                .toList();
    }

    // Done once inventory's consumer group has committed an offset past this message
    private void awaitConsumed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", connection.getBootstrapServers())))) {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets("inventory-service")
                        .partitionsToOffsetAndMetadata().get().get(partition);
                return committed != null && committed.offset() > sent.offset();
            });
        }
    }

    private int reservedUnits(String sku) {
        return productRepository.findById(sku).orElseThrow().getReserved();
    }

    private int stock(String sku) {
        return productRepository.findById(sku).orElseThrow().getStock();
    }

    private String reservationStatus(UUID order) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = :order")
                .param("order", order).query(String.class).single();
    }

    // Every event inventory wrote for this order, sent or not yet: checked right away, with no wait for the publisher
    private List<String> outboxEvents(UUID order) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :order ORDER BY created_at")
                .param("order", order).query(String.class).list();
    }

    private long reservationRows(UUID order) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE order_id = :order")
                .param("order", order).query(Long.class).single();
    }
}
