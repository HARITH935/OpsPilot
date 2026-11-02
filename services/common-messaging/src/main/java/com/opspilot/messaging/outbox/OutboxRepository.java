package com.opspilot.messaging.outbox;

import com.opspilot.events.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes the outbox table with plain SQL. Every service has the same table (in its V1 migration):
 * id = the event id, payload = the whole envelope as JSON, published_at stays empty until Kafka has the event.
 */
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(EventEnvelope envelope, String json) {
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, event_type, payload, created_at)
                        VALUES (:id, :aggregateId, :eventType, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("id", envelope.eventId())
                .param("aggregateId", envelope.aggregateId())
                .param("eventType", envelope.eventType())
                .param("payload", json)
                .param("createdAt", envelope.occurredAt().atOffset(ZoneOffset.UTC))   // the JDBC type for TIMESTAMPTZ
                .update();
    }

    /**
     * The oldest unsent rows, locked until the current transaction ends. SKIP LOCKED: rows that another publisher
     * (a second copy of the service) has locked are skipped instead of waited for, so no row is sent twice at once.
     */
    List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("payload")))
                .list();
    }

    void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;   // "IN ()" is not valid SQL
        }
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids)   // the list becomes IN (?, ?, ...)
                .update();
    }
}
