package com.opspilot.inventory;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.EventPayload;
import com.opspilot.events.OrderCreated;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.StockReleased;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.repository.ProductRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
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

// Inventory's last saga step, end to end: the test places a real reservation (OrderCreated on orders.events), then
// plays payment and sends PaymentCompleted / PaymentFailed on payments.events. Kafka and Postgres are real.
// Same setup as OrderEventsIntegrationTest, so both share one Spring context.
@SpringBootTest
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class PaymentEventsIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void paymentCompletedTakesTheHeldUnitsOutOfStock() throws Exception {
        String sku = newProduct(10);
        UUID order = reserve(sku, 2);

        send(EventEnvelope.of(new PaymentCompleted(order, UUID.randomUUID(), new BigDecimal("20.00")), null));

        awaitReservation(order, "COMMITTED");
        Product product = productRepository.findById(sku).orElseThrow();
        assertThat(product.getStock()).isEqualTo(8);   // the 2 paid units are gone for good
        assertThat(product.getReserved()).isZero();
        assertThat(replies(order)).hasSize(1);   // only StockReserved: a commit announces nothing
    }

    @Test
    void paymentFailedPutsTheUnitsBackOnSaleAndAnnouncesStockReleased() throws Exception {
        String sku = newProduct(10);
        UUID order = reserve(sku, 2);

        send(EventEnvelope.of(new PaymentFailed(order, UUID.randomUUID(), new BigDecimal("20.00"), "PROVIDER_ERROR"), null));

        awaitReservation(order, "RELEASED");
        Product product = productRepository.findById(sku).orElseThrow();
        assertThat(product.getStock()).isEqualTo(10);
        assertThat(product.getAvailable()).isEqualTo(10);   // all 10 can be sold again
        assertThat(awaitReplies(order, 2).get(1)).isEqualTo(new StockReleased(order));
    }

    @Test
    void theSamePaymentEventDeliveredTwiceReleasesOnce() throws Exception {
        String sku = newProduct(10);
        UUID order = reserve(sku, 2);
        EventEnvelope failed = EventEnvelope.of(new PaymentFailed(order, UUID.randomUUID(), BigDecimal.TEN, "X"), null);

        send(failed);
        awaitConsumed(send(failed));   // the same event id again, as an at-least-once redelivery would

        assertThat(productRepository.findById(sku).orElseThrow().getAvailable()).isEqualTo(10);   // not 12
        assertThat(awaitReplies(order, 2)).hasSize(2);   // StockReserved + one StockReleased
        assertThat(TopicReader.readAll(connection.getBootstrapServers(), Topics.PAYMENTS + ".dlq"))
                .filteredOn(message -> order.toString().equals(message.key()))
                .isEmpty();
    }

    // A real reservation through the first saga step, so the payment events have held stock to settle
    private UUID reserve(String sku, int qty) throws Exception {
        UUID order = UUID.randomUUID();
        kafka.send(Topics.ORDERS, order.toString(), EventJson.write(EventEnvelope.of(
                new OrderCreated(order, "c-1", List.of(new OrderCreated.Item(sku, qty))), null))).get();
        assertThat(awaitReplies(order, 1).get(0)).isInstanceOf(StockReserved.class);
        return order;
    }

    private String newProduct(int stock) {
        String sku = "SKU-T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        productRepository.save(new Product(sku, "Test product", new BigDecimal("10.00"), stock));
        return sku;
    }

    private RecordMetadata send(EventEnvelope event) throws Exception {
        return kafka.send(Topics.PAYMENTS, event.aggregateId().toString(), EventJson.write(event)).get().getRecordMetadata();
    }

    // The listener's first start takes a few seconds (the consumer group forms)
    private void awaitReservation(UUID order, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> jdbc
                .sql("SELECT status FROM reservations WHERE order_id = :order")
                .param("order", order).query(String.class).list().equals(List.of(status)));
    }

    // Inventory's events for this order, oldest first (they go out through the outbox, ~0.5 s)
    private List<EventPayload> awaitReplies(UUID order, int count) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> replies(order).size() >= count);
        return replies(order);
    }

    private List<EventPayload> replies(UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.INVENTORY).stream()
                .filter(message -> order.toString().equals(message.key()))
                .map(message -> EventJson.read(message.value()).payload())
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
}
