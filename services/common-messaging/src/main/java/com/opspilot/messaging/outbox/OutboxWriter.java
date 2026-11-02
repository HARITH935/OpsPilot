package com.opspilot.messaging.outbox;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.EventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves an event in the outbox table inside the caller's database transaction, so the business change and its event
 * are committed together, or neither is (the transactional outbox). OutboxPublisher sends it to Kafka afterwards.
 */
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxRepository repository;

    public OutboxWriter(OutboxRepository repository) {
        this.repository = repository;
    }

    // MANDATORY: refuse to run without a transaction. On its own, the event could not be rolled back together
    // with the change it describes.
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope write(EventPayload payload) {
        EventEnvelope envelope = EventEnvelope.of(payload, null);   // trace id: added with OpenTelemetry in Phase 6
        repository.insert(envelope, EventJson.write(envelope));
        log.debug("Saved {} {} for order {} in the outbox", envelope.eventType(), envelope.eventId(), envelope.aggregateId());
        return envelope;
    }
}
