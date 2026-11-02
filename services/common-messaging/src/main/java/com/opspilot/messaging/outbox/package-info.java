/**
 * The transactional outbox: {@link com.opspilot.messaging.outbox.OutboxWriter} saves an event in the same database
 * transaction as the business change; {@link com.opspilot.messaging.outbox.OutboxPublisher} later sends it to Kafka.
 */
package com.opspilot.messaging.outbox;
