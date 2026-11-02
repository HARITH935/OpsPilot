package com.opspilot.messaging;

import com.opspilot.events.EventEnvelope;
import com.opspilot.events.EventJson;
import com.opspilot.events.OrderCancelled;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// A listener for the dead-letter tests, on its own topic: counts every delivery per key, fails on purpose for
// OrderCancelled (a failure that retrying can't fix), and fails while reading anything that isn't an event
public class TestListener {

    public static final String TOPIC = "messaging-test.events";

    private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

    @KafkaListener(topics = TOPIC)
    void on(ConsumerRecord<String, String> message) {
        deliveries.computeIfAbsent(message.key(), key -> new AtomicInteger()).incrementAndGet();
        EventEnvelope event = EventJson.read(message.value());   // not JSON: throws here
        if (event.payload() instanceof OrderCancelled) {
            throw new IllegalStateException("Simulated failure for order " + message.key());
        }
    }

    public int deliveriesOf(String key) {
        AtomicInteger count = deliveries.get(key);
        return count == null ? 0 : count.get();
    }
}
