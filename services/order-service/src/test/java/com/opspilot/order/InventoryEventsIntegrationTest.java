package com.opspilot.order;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderCancelled;
import com.opspilot.events.StockRejected;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Order's reaction to inventory, end to end: a real order over HTTP, then the test plays inventory and sends
// StockReserved / StockRejected on inventory.events. Kafka and Postgres are real (Testcontainers).
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class InventoryEventsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Test
    void stockReservedCopiesThePricesOntoTheOrder() throws Exception {
        UUID order = placeOrder();

        send(EventEnvelope.of(new StockReserved(order,
                List.of(new StockReserved.Item("SKU-1", 2, new BigDecimal("89.99"))), new BigDecimal("179.98")), null));

        awaitOrder(order, "$.totalAmount", 179.98);
        mockMvc.perform(get("/api/orders/{id}", order))
                .andExpect(jsonPath("$.status").value("PENDING"))   // priced; confirmed only once it is paid (2.5)
                .andExpect(jsonPath("$.items[0].unitPrice").value(89.99));
    }

    @Test
    void stockRejectedCancelsTheOrderAndAnnouncesOrderCancelled() throws Exception {
        UUID order = placeOrder();

        send(EventEnvelope.of(new StockRejected(order, "SKU-1", "OUT_OF_STOCK"), null));

        awaitOrder(order, "$.status", "CANCELLED");
        mockMvc.perform(get("/api/orders/{id}", order)).andExpect(jsonPath("$.cancelReason").value("OUT_OF_STOCK"));
        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderCancelled");
    }

    @Test
    void theSameEventDeliveredTwiceIsAppliedOnce() throws Exception {
        UUID order = placeOrder();
        EventEnvelope rejected = EventEnvelope.of(new StockRejected(order, "SKU-1", "OUT_OF_STOCK"), null);

        send(rejected);
        awaitConsumed(send(rejected));   // the same event id again, as an at-least-once redelivery would

        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderCancelled");   // one cancel
        // Without the duplicate check, the second cancel would fail ("already CANCELLED") and land in the DLQ
        assertThat(TopicReader.readAll(connection.getBootstrapServers(), Topics.INVENTORY + ".dlq"))
                .filteredOn(message -> order.toString().equals(message.key()))
                .isEmpty();
        OrderCancelled cancelled = (OrderCancelled) EventJson.read(
                TopicReader.readAll(connection.getBootstrapServers(), Topics.ORDERS).stream()
                        .filter(message -> order.toString().equals(message.key())).toList().get(1).value()).payload();
        assertThat(cancelled.reason()).isEqualTo("OUT_OF_STOCK");
    }

    private UUID placeOrder() throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c-inventory\",\"items\":[{\"sku\":\"SKU-1\",\"qty\":2}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.orderId"));
    }

    private RecordMetadata send(EventEnvelope event) throws Exception {
        return kafka.send(Topics.INVENTORY, event.aggregateId().toString(), EventJson.write(event)).get().getRecordMetadata();
    }

    // The listener's first start takes a few seconds (the consumer group forms)
    private void awaitOrder(UUID order, String field, Object expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                mockMvc.perform(get("/api/orders/{id}", order)).andExpect(jsonPath(field).value(expected)));
    }

    // The event types order-service sent for this order, oldest first (OrderCancelled goes out through the outbox)
    private List<String> awaitOrderEvents(UUID order, int count) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> orderEvents(order).size() >= count);
        return orderEvents(order);
    }

    private List<String> orderEvents(UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.ORDERS).stream()
                .filter(message -> order.toString().equals(message.key()))
                .map(message -> EventJson.read(message.value()).eventType())
                .toList();
    }

    // Done once order-service's consumer group has committed an offset past this message
    private void awaitConsumed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", connection.getBootstrapServers())))) {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets("order-service")
                        .partitionsToOffsetAndMetadata().get().get(partition);
                return committed != null && committed.offset() > sent.offset();
            });
        }
    }
}
