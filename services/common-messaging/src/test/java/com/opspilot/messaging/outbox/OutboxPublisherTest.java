package com.opspilot.messaging.outbox;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderConfirmed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The publisher's decisions, with Kafka and the database replaced by Mockito mocks: fast, and failures on demand
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafka;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, kafka, TransactionOperations.withoutTransaction());
    }

    @Test
    void sendsToTheEventsTopicWithTheOrderIdAsKey() {
        OutboxRow row = row();
        EventEnvelope envelope = EventJson.read(row.payload());
        when(repository.lockUnpublished(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(row));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(publisher.publishPending()).isEqualTo(1);

        verify(kafka).send("orders.events", envelope.aggregateId().toString(), EventJson.write(envelope));
        verify(repository).markPublished(List.of(row.id()));
    }

    @Test
    void stopsAtTheFirstFailedSendAndMarksOnlyWhatKafkaStored() {
        OutboxRow first = row();
        OutboxRow second = row();
        OutboxRow third = row();
        when(repository.lockUnpublished(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(first, second, third));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null))   // first: stored by Kafka
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker gone")));   // second: lost

        assertThat(publisher.publishPending()).isEqualTo(1);

        verify(kafka, times(2)).send(anyString(), anyString(), anyString());   // the third was not tried: no overtaking
        verify(repository).markPublished(List.of(first.id()));   // second and third stay unsent, for the next run
    }

    @Test
    void kafkaUnreachableLeavesEverythingUnsent() {
        when(repository.lockUnpublished(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(row(), row()));
        // What KafkaTemplate throws when it cannot reach Kafka within max.block.ms
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new KafkaException("Send failed"));

        assertThat(publisher.publishPending()).isZero();

        verify(repository).markPublished(List.of());
    }

    @Test
    void aFullBatchIsFollowedByTheNextOneAtOnce() {
        List<OutboxRow> fullBatch = IntStream.range(0, OutboxPublisher.BATCH_SIZE).mapToObj(i -> row()).toList();
        when(repository.lockUnpublished(OutboxPublisher.BATCH_SIZE)).thenReturn(fullBatch, List.of(row()));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(publisher.publishPending()).isEqualTo(OutboxPublisher.BATCH_SIZE + 1);

        verify(repository, times(2)).lockUnpublished(OutboxPublisher.BATCH_SIZE);   // a short batch ends the run
    }

    private static OutboxRow row() {
        EventEnvelope envelope = EventEnvelope.of(new OrderConfirmed(UUID.randomUUID()), null);
        return new OutboxRow(envelope.eventId(), EventJson.write(envelope));
    }
}
