package com.opspilot.order;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.order.repository.OrderRepository;
import com.opspilot.order.service.OrderService;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The saga's end for order, end to end: a real order over HTTP, then the test plays inventory and payment and sends
// their events. Kafka and Postgres are real (Testcontainers). Same setup as InventoryEventsIntegrationTest, so both
// share one Spring context.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class PaymentEventsIntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("179.98");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionOperations transactions;

    @Test
    void paymentCompletedConfirmsTheOrderAndAnnouncesOrderConfirmed() throws Exception {
        UUID order = placeOrder();
        send(Topics.INVENTORY, stockReserved(order));
        awaitOrder(order, "$.totalAmount", 179.98);

        send(Topics.PAYMENTS, EventEnvelope.of(new PaymentCompleted(order, UUID.randomUUID(), TOTAL), null));

        awaitOrder(order, "$.status", "CONFIRMED");
        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderConfirmed");
    }

    @Test
    void paymentFailedCancelsTheOrderAndAnnouncesOrderCancelled() throws Exception {
        UUID order = placeOrder();
        send(Topics.INVENTORY, stockReserved(order));

        send(Topics.PAYMENTS, EventEnvelope.of(new PaymentFailed(order, UUID.randomUUID(), TOTAL, "PROVIDER_ERROR"), null));

        awaitOrder(order, "$.status", "CANCELLED");
        mockMvc.perform(get("/api/orders/{id}", order)).andExpect(jsonPath("$.cancelReason").value("PAYMENT_FAILED"));
        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderCancelled");
    }

    @Test
    void aPaymentResultThatOvertakesThePriceEndsTheSame() throws Exception {
        UUID order = placeOrder();

        // The payment result is applied first, then the price (they come on different topics, so this can happen)
        send(Topics.PAYMENTS, EventEnvelope.of(new PaymentCompleted(order, UUID.randomUUID(), TOTAL), null));
        awaitOrder(order, "$.status", "CONFIRMED");
        awaitConsumed(send(Topics.INVENTORY, stockReserved(order)));

        mockMvc.perform(get("/api/orders/{id}", order))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(179.98))
                .andExpect(jsonPath("$.items[0].unitPrice").value(89.99));
        assertThat(deadLetters(Topics.INVENTORY, order)).isEmpty();   // the late price was applied, not parked
    }

    @Test
    void theSamePaymentEventDeliveredTwiceIsAppliedOnce() throws Exception {
        UUID order = placeOrder();
        EventEnvelope completed = EventEnvelope.of(new PaymentCompleted(order, UUID.randomUUID(), TOTAL), null);

        send(Topics.PAYMENTS, completed);
        awaitConsumed(send(Topics.PAYMENTS, completed));   // the same event id again, as an at-least-once redelivery would

        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderConfirmed");   // one confirm
        // Without the duplicate check, the second confirm would fail ("already CONFIRMED") and land in the DLQ
        assertThat(deadLetters(Topics.PAYMENTS, order)).isEmpty();
    }

    @Test
    void whenThePaymentResultAndTheSagaTimeoutCollideTheFirstToSaveWins() throws Exception {
        UUID order = placeOrder();

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            // The timeout job's transaction has read the order (PENDING, version 0)...
            orderRepository.findById(order).orElseThrow();
            // ...when the payment result is applied and saved first, on another thread (version 0 -> 1)
            CompletableFuture.runAsync(() -> orderService.onPaymentCompleted(UUID.randomUUID(),
                    new PaymentCompleted(order, UUID.randomUUID(), TOTAL))).join();
            orderService.cancelTimedOut(order);   // the job still has its PENDING copy, so it cancels it...
        })).isInstanceOf(OptimisticLockingFailureException.class);   // ...but the save finds version 1, not 0

        // The payment won, and the job's whole transaction was rolled back: no TIMEOUT, no OrderCancelled sent
        mockMvc.perform(get("/api/orders/{id}", order)).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(awaitOrderEvents(order, 2)).containsExactly("OrderCreated", "OrderConfirmed");
    }

    private UUID placeOrder() throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c-payment\",\"items\":[{\"sku\":\"SKU-1\",\"qty\":2}]}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.orderId"));
    }

    private static EventEnvelope stockReserved(UUID order) {
        return EventEnvelope.of(new StockReserved(order,
                List.of(new StockReserved.Item("SKU-1", 2, new BigDecimal("89.99"))), TOTAL), null);
    }

    private RecordMetadata send(String topic, EventEnvelope event) throws Exception {
        return kafka.send(topic, event.aggregateId().toString(), EventJson.write(event)).get().getRecordMetadata();
    }

    // The listener's first start takes a few seconds (the consumer group forms)
    private void awaitOrder(UUID order, String field, Object expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
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
