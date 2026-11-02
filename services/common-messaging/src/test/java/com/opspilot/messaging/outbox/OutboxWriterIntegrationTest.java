package com.opspilot.messaging.outbox;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderConfirmed;
import com.opspilot.messaging.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxWriterIntegrationTest {

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyTheOutbox() {
        jdbc.sql("DELETE FROM outbox").update();
    }

    @Test
    void savesTheWholeEnvelopeAsAnUnsentRow() {
        UUID orderId = UUID.randomUUID();

        EventEnvelope envelope = transactions.execute(tx -> writer.write(new OrderConfirmed(orderId)));

        Map<String, Object> row = jdbc.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload, created_at, published_at
                        FROM outbox""")
                .query().singleRow();
        assertThat(row.get("id")).isEqualTo(envelope.eventId());   // the row id is the event id
        assertThat(row.get("aggregate_id")).isEqualTo(orderId);
        assertThat(row.get("event_type")).isEqualTo("OrderConfirmed");
        assertThat(((Timestamp) row.get("created_at")).toInstant())
                .isCloseTo(envelope.occurredAt(), within(1, ChronoUnit.MICROS));   // Postgres keeps microseconds
        assertThat(row.get("published_at")).isNull();   // not sent yet
        assertThat(EventJson.read((String) row.get("payload"))).isEqualTo(envelope);
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> writer.write(new OrderConfirmed(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    void aRolledBackTransactionTakesItsEventWithIt() {
        transactions.executeWithoutResult(tx -> {
            writer.write(new OrderConfirmed(UUID.randomUUID()));
            tx.setRollbackOnly();   // as if the business change failed after the event was written
        });

        assertThat(rowCount()).isZero();   // no event about a change that never happened
    }

    private long rowCount() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }
}
