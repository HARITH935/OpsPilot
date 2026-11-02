package com.opspilot.messaging.outbox;

import java.util.UUID;

// One unsent outbox row: the event id and the envelope JSON as stored
record OutboxRow(UUID id, String payload) {
}
