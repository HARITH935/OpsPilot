package com.opspilot.messaging.outbox;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends the events saved by OutboxWriter to Kafka, oldest first, about every 0.5 s. A row is marked published only
 * after Kafka has stored the event; if the service stops in between, the event is sent again later. So delivery is
 * at-least-once, and consumers must ignore duplicates (by eventId).
 */
public class OutboxPublisher {

    static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 15;   // a bit longer than the producer's own 10 s delivery limit

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionOperations transactions;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                           TransactionOperations transactions) {
        this.repository = repository;
        this.kafka = kafka;
        this.transactions = transactions;
    }

    /**
     * Sends everything waiting and returns how many events were sent. Runs on its own once the service has
     * scheduling turned on (@EnableScheduling); tests call it directly.
     */
    @Scheduled(fixedDelay = 500)   // ms between the end of one run and the start of the next
    public int publishPending() {
        int total = 0;
        int sent;
        do {
            sent = transactions.execute(tx -> publishBatch());   // one transaction per batch: lock, send, mark
            total += sent;
        } while (sent == BATCH_SIZE);   // a full batch: more may be waiting, so go again without pausing
        return total;
    }

    private int publishBatch() {
        List<UUID> published = new ArrayList<>();
        for (OutboxRow row : repository.lockUnpublished(BATCH_SIZE)) {
            // Postgres hands jsonb back with its keys reordered: read it and write it again in the contract's format
            EventEnvelope envelope = EventJson.read(row.payload());
            String topic = EventType.fromWireName(envelope.eventType()).topic();
            try {
                // Key = the order id: Kafka keeps all events of one order in one partition, in order
                kafka.send(topic, envelope.aggregateId().toString(), EventJson.write(envelope))
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);   // wait until Kafka has stored it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // the service is shutting down
                break;
            } catch (KafkaException | ExecutionException | TimeoutException e) {
                // Stop here, so no later event can overtake this one. It stays unsent and is retried in the next run.
                log.warn("Could not send {} {} to {}, will retry: {}", envelope.eventType(), envelope.eventId(), topic,
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
                break;
            }
            published.add(row.id());
            log.debug("Sent {} {} to {}", envelope.eventType(), envelope.eventId(), topic);
        }
        repository.markPublished(published);   // committed together with the end of the batch's transaction
        return published.size();
    }
}
