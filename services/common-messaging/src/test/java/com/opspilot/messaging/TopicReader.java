package com.opspilot.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Test helper: reads every message that is in a topic right now, from the first to the last, then stops
public final class TopicReader {

    private TopicReader() {
    }

    public static List<ConsumerRecord<String, String>> readAll(List<String> bootstrapServers, String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", bootstrapServers),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);   // read the partitions directly: no consumer group, nothing committed
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);   // where each partition ends right now

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Timed out reading " + topic);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}
