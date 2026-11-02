package com.opspilot.order;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderCreated;
import com.opspilot.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The saga's first step, end to end: HTTP request -> order + outbox row in one transaction -> publisher -> Kafka
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class OrderEventsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaConnectionDetails kafka;

    @Test
    void placingAnOrderPublishesOrderCreatedKeyedByTheOrderId() throws Exception {
        UUID orderId = placeOrder("c-events-1", null);

        awaitPublished(orderId);
        List<ConsumerRecord<String, String>> messages = messagesFor(orderId);
        assertThat(messages).hasSize(1);
        EventEnvelope envelope = EventJson.read(messages.get(0).value());
        assertThat(envelope.eventType()).isEqualTo("OrderCreated");
        assertThat(envelope.payload())
                .isEqualTo(new OrderCreated(orderId, "c-events-1", List.of(new OrderCreated.Item("SKU-1", 2))));
    }

    @Test
    void retryingWithTheSameIdempotencyKeyPublishesNoSecondEvent() throws Exception {
        UUID first = placeOrder("c-events-2", "events-key-1");
        UUID retry = placeOrder("c-events-2", "events-key-1");
        assertThat(retry).isEqualTo(first);   // the same order came back

        awaitPublished(first);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :order")
                .param("order", first).query(Long.class).single()).isEqualTo(1);
        assertThat(messagesFor(first)).hasSize(1);
    }

    private UUID placeOrder(String customerId, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\",\"items\":[{\"sku\":\"SKU-1\",\"qty\":2}]}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        String body = mockMvc.perform(request).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.orderId"));
    }

    // The publisher runs in the background every 0.5 s. Once the row is marked published, Kafka has the event.
    private void awaitPublished(UUID orderId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM outbox WHERE aggregate_id = :order AND published_at IS NULL""")
                .param("order", orderId).query(Long.class).single() == 0);
    }

    // Every message on orders.events for this order (the topic also holds other tests' messages)
    private List<ConsumerRecord<String, String>> messagesFor(UUID orderId) {
        return TopicReader.readAll(kafka.getBootstrapServers(), Topics.ORDERS).stream()
                .filter(message -> message.key().equals(orderId.toString()))
                .toList();
    }
}
