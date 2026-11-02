/**
 * Messaging support shared by all services: the transactional outbox (writer + Kafka publisher) today, idempotent
 * consumers next (step 2.3). A service uses it by importing {@link com.opspilot.messaging.MessagingConfiguration}.
 */
package com.opspilot.messaging;
