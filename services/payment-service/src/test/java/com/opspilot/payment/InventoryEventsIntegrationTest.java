package com.opspilot.payment;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.EventPayload;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.StockRejected;
import com.opspilot.events.StockReserved;
import com.opspilot.events.Topics;
import com.opspilot.payment.exception.PaymentProviderException;
import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;
import com.opspilot.payment.repository.PaymentRepository;
import com.opspilot.payment.service.PaymentProvider;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Payment's saga step, end to end: StockReserved on inventory.events -> listener -> provider -> payment + outbox ->
// PaymentCompleted / PaymentFailed on payments.events. Kafka and Postgres are real; the provider is a mock, so each
// test decides whether it succeeds. Same setup as PaymentApiIntegrationTest, so both share one Spring context.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class InventoryEventsIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentProvider paymentProvider;

    @Test
    void stockReservedIsChargedAndAnsweredWithPaymentCompleted() throws Exception {
        UUID order = UUID.randomUUID();
        BigDecimal total = new BigDecimal("25.00");

        send(stockReserved(order, total));

        Payment payment = awaitPayment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getAmount()).isEqualByComparingTo(total);   // exactly inventory's total (ADR 0004)
        verify(paymentProvider).charge(order, total);
        assertThat(awaitReply(order)).isEqualTo(new PaymentCompleted(order, payment.getId(), total));
    }

    @Test
    void aFailingProviderIsAnsweredWithPaymentFailed() throws Exception {
        UUID order = UUID.randomUUID();
        BigDecimal total = new BigDecimal("10.00");
        doThrow(new PaymentProviderException("card declined")).when(paymentProvider).charge(eq(order), any());

        send(stockReserved(order, total));

        Payment payment = awaitPayment(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(awaitReply(order)).isEqualTo(new PaymentFailed(order, payment.getId(), total, "PROVIDER_ERROR"));
    }

    @Test
    void theSameEventDeliveredTwiceChargesOnce() throws Exception {
        UUID order = UUID.randomUUID();
        BigDecimal total = new BigDecimal("5.00");
        String event = stockReserved(order, total);

        send(event);
        awaitConsumed(send(event));   // the same event id again, as an at-least-once redelivery would

        verify(paymentProvider, times(1)).charge(order, total);   // the customer is charged once
        awaitReply(order);
        assertThat(replies(order)).hasSize(1);
        assertThat(paymentRepository.findAll()).filteredOn(payment -> payment.getOrderId().equals(order)).hasSize(1);
    }

    @Test
    void aRejectedOrderIsNotCharged() throws Exception {
        UUID order = UUID.randomUUID();

        awaitConsumed(send(EventJson.write(EventEnvelope.of(new StockRejected(order, "SKU-10", "OUT_OF_STOCK"), null))));

        verify(paymentProvider, never()).charge(eq(order), any());
        assertThat(paymentRepository.findByOrderId(order)).isEmpty();
    }

    private static String stockReserved(UUID order, BigDecimal total) {
        return EventJson.write(EventEnvelope.of(
                new StockReserved(order, List.of(new StockReserved.Item("SKU-1", 1, total)), total), null));
    }

    private RecordMetadata send(String event) throws Exception {
        return kafka.send(Topics.INVENTORY, EventJson.read(event).aggregateId().toString(), event)
                .get().getRecordMetadata();
    }

    // The listener's first start takes a few seconds (the consumer group forms)
    private Payment awaitPayment(UUID order) {
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> paymentRepository.findByOrderId(order).orElse(null), payment -> payment != null);
    }

    // The outbox publisher sends the answer within about 0.5 s
    private EventPayload awaitReply(UUID order) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> !replies(order).isEmpty());
        return replies(order).get(0);
    }

    private List<EventPayload> replies(UUID order) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.PAYMENTS).stream()
                .filter(message -> order.toString().equals(message.key()))
                .map(message -> EventJson.read(message.value()).payload())
                .toList();
    }

    // Done once payment's consumer group has committed an offset past this message
    private void awaitConsumed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", connection.getBootstrapServers())))) {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets("payment-service")
                        .partitionsToOffsetAndMetadata().get().get(partition);
                return committed != null && committed.offset() > sent.offset();
            });
        }
    }
}
