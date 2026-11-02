package com.opspilot.messaging.consumer;

import com.opspilot.messaging.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProcessedEventsIntegrationTest {

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private TransactionOperations transactions;

    @Test
    void trueTheFirstTimeFalseForADuplicate() {
        UUID eventId = UUID.randomUUID();

        assertThat(mark(eventId)).isTrue();    // new: handle it
        assertThat(mark(eventId)).isFalse();   // seen before: skip it
        assertThat(mark(UUID.randomUUID())).isTrue();
    }

    @Test
    void aFailedHandlingUnrecordsTheEventSoTheRetryRunsAgain() {
        UUID eventId = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> {
            assertThat(processedEvents.markProcessed(eventId)).isTrue();
            tx.setRollbackOnly();   // the handling failed after the check
        });

        assertThat(mark(eventId)).isTrue();   // the redelivered event is handled, not skipped
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> processedEvents.markProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private boolean mark(UUID eventId) {
        return transactions.execute(tx -> processedEvents.markProcessed(eventId));
    }
}
