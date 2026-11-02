package com.opspilot.messaging.consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The duplicate check of an idempotent consumer. Kafka delivers at-least-once, so the same event can arrive twice;
 * the processed_events table (in every service's V1 migration) remembers which event ids were already handled.
 */
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as handled. True the first time, false for a duplicate. MANDATORY: it must share the
     * transaction of the handling itself, so a failed handling also un-records the event and the retry runs again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId) {
        // One statement, safe when two consumers race: the primary key lets only one insert win
        int inserted = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT (event_id) DO NOTHING")
                .param("id", eventId)
                .update();
        return inserted == 1;
    }
}
