package com.opspilot.order;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentRefunded;
import com.opspilot.events.RefundRequested;
import com.opspilot.events.Topics;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The saga timeout, end to end: real orders over HTTP, and nobody answers them (as if inventory and payment were
// down). The timeout is shortened to 2 s, checked every 0.2 s, so the test is quick. Different settings mean a Spring
// context of its own, with its own Kafka and Postgres containers.
@SpringBootTest(properties = {"order.saga.timeout=2s", "order.saga.check-interval=200ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class SagaTimeoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Test
    void anOrderNobodyAnswersIsCancelledOnceTheTimeoutHasPassed() throws Exception {
        UUID order = placeOrder();

        awaitOrder(order, "$.status", "CANCELLED");

        String body = mockMvc.perform(get("/api/orders/{id}", order))
                .andExpect(jsonPath("$.cancelReason").value("TIMEOUT"))
                .andReturn().getResponse().getContentAsString();
        Instant created = Instant.parse(JsonPath.read(body, "$.createdAt"));
        Instant cancelled = Instant.parse(JsonPath.read(body, "$.updatedAt"));
        assertThat(Duration.between(created, cancelled)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));   // not sooner
        // OrderCancelled goes out, so inventory releases whatever it holds for the order
        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderCancelled");
    }

    @Test
    void aPaymentThatArrivesAfterTheTimeoutIsSentBackForARefund() throws Exception {
        UUID order = placeOrder();
        UUID payment = UUID.randomUUID();
        awaitOrder(order, "$.cancelReason", "TIMEOUT");

        awaitConsumed(send(Topics.PAYMENTS,
                EventEnvelope.of(new PaymentCompleted(order, payment, new BigDecimal("179.98")), null)));

        mockMvc.perform(get("/api/orders/{id}", order))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("TIMEOUT"));
        // No OrderConfirmed: a refund request for exactly that payment instead (ADR 0006)
        assertThat(awaitOrderEvents(order, 3)).containsExactly("OrderCreated", "OrderCancelled", "RefundRequested");
        assertThat(lastOrderEvent(order)).isEqualTo(new RefundRequested(order, payment));
        assertThat(deadLetters(Topics.PAYMENTS, order)).isEmpty();   // handled, not failed and parked

        // payment-service's answer: order-service has nothing to do, and must not choke on the new event either
        awaitConsumed(send(Topics.PAYMENTS,
                EventEnvelope.of(new PaymentRefunded(order, payment, new BigDecimal("179.98")), null)));
        assertThat(orderEvents(order)).hasSize(3);
        assertThat(deadLetters(Topics.PAYMENTS, order)).isEmpty();
    }

    private UUID placeOrder() throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c-timeout\",\"items\":[{\"sku\":\"SKU-1\",\"qty\":2}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.orderId"));
    }

    private RecordMetadata send(String topic, EventEnvelope event) throws Exception {
        return kafka.send(topic, event.aggregateId().toString(), EventJson.write(event)).get().getRecordMetadata();
    }

    private void awaitOrder(UUID order, String field, Object expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                mockMvc.perform(get("/api/orders/{id}", order)).andExpect(jsonPath(field).value(expected)));
    }

    // The event types order-service sent for this order, oldest first (they go out through the outbox)
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

    private Object lastOrderEvent(UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.ORDERS).stream()
                .filter(message -> order.toString().equals(message.key()))
                .map(message -> (Object) EventJson.read(message.value()).payload())
                .toList().getLast();
    }

    private List<ConsumerRecord<String, String>> deadLetters(String topic, UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), topic + ".dlq").stream()
                .filter(message -> order.toString().equals(message.key()))
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
