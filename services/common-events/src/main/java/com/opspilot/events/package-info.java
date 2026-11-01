/**
 * The events the services send each other through Kafka: the envelope, the eight saga events, their names and topics,
 * and their JSON format. Every service uses this one module (from Phase 2), so a sender and a receiver always agree.
 */
package com.opspilot.events;
