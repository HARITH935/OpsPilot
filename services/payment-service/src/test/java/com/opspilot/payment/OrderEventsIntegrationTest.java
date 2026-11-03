package com.opspilot.payment;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.EventPayload;
import com.opspilot.events.OrderCreated;
import com.opspilot.events.PaymentRefunded;
import com.opspilot.events.RefundRequested;
import com.opspilot.events.Topics;
import com.opspilot.payment.exception.PaymentProviderException;
import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;
import com.opspilot.payment.repository.PaymentRepository;
import com.opspilot.payment.service.PaymentProvider;
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

// The refund, end to end (ADR 0006): RefundRequested on orders.events -> listener -> provider -> REFUNDED payment +
// outbox -> PaymentRefunded on payments.events. Kafka and Postgres are real; the provider is a mock. Same setup as
// the other full-app tests, so all of them share one Spring context.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class OrderEventsIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaConnectionDetails connection;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentProvider paymentProvider;

    @Test
    void refundRequestedGivesTheMoneyBackAndIsAnsweredWithPaymentRefunded() throws Exception {
        Payment payment = completedPayment("179.98");   // as if charged after the order had timed out

        send(refundRequested(payment));

        Payment refunded = awaitRefunded(payment);
        assertThat(refunded.getRefundedAt()).isNotNull();
        verify(paymentProvider).refund(payment.getOrderId(), new BigDecimal("179.98"));
        assertThat(awaitReply(payment)).isEqualTo(
                new PaymentRefunded(payment.getOrderId(), payment.getId(), new BigDecimal("179.98")));
    }

    @Test
    void theSameRequestDeliveredTwiceRefundsOnce() throws Exception {
        Payment payment = completedPayment("5.00");
        String request = refundRequested(payment);

        send(request);
        awaitConsumed(send(request));   // the same event id again, as an at-least-once redelivery would

        verify(paymentProvider, times(1)).refund(payment.getOrderId(), new BigDecimal("5.00"));   // the money goes back once
        awaitReply(payment);
        assertThat(replies(payment)).hasSize(1);
        assertThat(deadLetters(payment)).isEmpty();   // the repeat was skipped, not failed and parked
    }

    @Test
    void aRefundTheProviderKeepsRefusingIsParkedInTheDeadLetterTopic() throws Exception {
        Payment payment = completedPayment("10.00");
        doThrow(new PaymentProviderException("provider down")).when(paymentProvider).refund(eq(payment.getOrderId()), any());

        send(refundRequested(payment));

        // The first try and 3 retries (after 0.5, 1 and 2 s), then the request is moved to orders.events.dlq
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> !deadLetters(payment).isEmpty());
        verify(paymentProvider, times(4)).refund(payment.getOrderId(), new BigDecimal("10.00"));
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);   // nothing was saved: a human has to look at it
        assertThat(replies(payment)).isEmpty();
    }

    @Test
    void theOtherOrderEventsAreIgnored() throws Exception {
        UUID order = UUID.randomUUID();

        awaitConsumed(kafka.send(Topics.ORDERS, order.toString(), EventJson.write(EventEnvelope.of(
                new OrderCreated(order, "c-1", List.of(new OrderCreated.Item("SKU-1", 1))), null))).get().getRecordMetadata());

        verify(paymentProvider, never()).charge(eq(order), any());   // payment starts from StockReserved, not this
        assertThat(paymentRepository.findByOrderId(order)).isEmpty();
    }

    private Payment completedPayment(String amount) {
        return paymentRepository.saveAndFlush(Payment.completed(UUID.randomUUID(), new BigDecimal(amount)));
    }

    private static String refundRequested(Payment payment) {
        return EventJson.write(EventEnvelope.of(new RefundRequested(payment.getOrderId(), payment.getId()), null));
    }

    private RecordMetadata send(String event) throws Exception {
        return kafka.send(Topics.ORDERS, EventJson.read(event).aggregateId().toString(), event)
                .get().getRecordMetadata();
    }

    // The listener's first start takes a few seconds (the consumer group forms)
    private Payment awaitRefunded(Payment payment) {
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .until(() -> paymentRepository.findById(payment.getId()).orElseThrow(),
                        stored -> stored.getStatus() == PaymentStatus.REFUNDED);
    }

    // The outbox publisher sends the answer within about 0.5 s
    private EventPayload awaitReply(Payment payment) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> !replies(payment).isEmpty());
        return replies(payment).get(0);
    }

    private List<EventPayload> replies(Payment payment) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.PAYMENTS).stream()
                .filter(message -> payment.getOrderId().toString().equals(message.key()))
                .map(message -> EventJson.read(message.value()).payload())
                .toList();
    }

    private List<ConsumerRecord<String, String>> deadLetters(Payment payment) {
        return TopicReader.readAll(connection.getBootstrapServers(), Topics.ORDERS + ".dlq").stream()
                .filter(message -> payment.getOrderId().toString().equals(message.key()))
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
