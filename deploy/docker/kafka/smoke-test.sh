#!/usr/bin/env bash
# Smoke test for the local Kafka. Run it after `docker compose -f deploy/docker/docker-compose.yml up -d`:
#   deploy/docker/kafka/smoke-test.sh
# Checks: every topic in topics.txt exists with 3 partitions; messages with the same key land in one partition,
# in the order sent; a misspelled topic is refused; the port the services use is reachable.
# It writes only to a throwaway topic (deleted at the end), never to the real ones.
set -euo pipefail

TOPICS_FILE="$(dirname "$0")/topics.txt"
CONTAINER=opspilot-kafka
TEST_TOPIC="smoke-test-$(date +%s)"

# Runs one of Kafka's command-line tools inside the Kafka container, e.g.: kafka_cli kafka-topics.sh --list
kafka_cli() {
  local tool="$1"
  shift
  docker exec -i "$CONTAINER" "/opt/kafka/bin/$tool" --bootstrap-server localhost:9092 "$@"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" = healthy ] ||
  fail "$CONTAINER is not running and healthy; start it with: docker compose -f deploy/docker/docker-compose.yml up -d"

# kafka-init creates the topics just after Kafka turns healthy: wait until it has finished (instant if it already has)
init_exit=$(docker wait opspilot-kafka-init 2>/dev/null) ||
  fail "opspilot-kafka-init not found; start it with: docker compose -f deploy/docker/docker-compose.yml up -d"
[ "$init_exit" = 0 ] || fail "kafka-init failed with exit code $init_exit; see: docker logs opspilot-kafka-init"

# 1. Every topic in topics.txt exists with 3 partitions
described=$(kafka_cli kafka-topics.sh --describe --exclude-internal)
count=0
for topic in $(grep -Ev '^[[:space:]]*(#|$)' "$TOPICS_FILE"); do
  # The topic's summary line reads: "Topic: <name>  TopicId: ...  PartitionCount: 3  ..."
  partitions=$(awk -v t="$topic" '$1 == "Topic:" && $2 == t {
    for (i = 3; i < NF; i++) if ($i == "PartitionCount:") print $(i + 1) }' <<<"$described")
  [ -n "$partitions" ] || fail "topic $topic does not exist"
  [ "$partitions" = 3 ] || fail "topic $topic has $partitions partitions, expected 3"
  count=$((count + 1))
done
echo "ok  $count topics from topics.txt exist, 3 partitions each"

# 2. Same key -> same partition, in the order sent (why the services use the order id as the key)
kafka_cli kafka-topics.sh --create --topic "$TEST_TOPIC" --partitions 3 --replication-factor 1 > /dev/null
trap 'kafka_cli kafka-topics.sh --delete --topic "$TEST_TOPIC" > /dev/null 2>&1 || true' EXIT   # clean up, even after a failure

# One "key:value" message per line, with the keys mixed up
printf '%s\n' order-A:A1 order-B:B1 order-A:A2 order-C:C1 order-B:B2 order-A:A3 |
  kafka_cli kafka-console-producer.sh --topic "$TEST_TOPIC" --reader-property parse.key=true --reader-property key.separator=:
# Each line read back: "Partition:<n> <tab> <key> <tab> <value>"; a missing message shows up in check_key below
received=$(kafka_cli kafka-console-consumer.sh --topic "$TEST_TOPIC" --from-beginning --max-messages 6 --timeout-ms 15000 \
  --formatter-property print.partition=true --formatter-property print.key=true 2>/dev/null || true)

check_key() {   # check_key <key> <the values sent with that key, in order>
  local key="$1" expected="$2" lines partitions values
  lines=$(awk -F '\t' -v k="$key" '$2 == k' <<<"$received")   # only this key's messages
  partitions=$(cut -f1 <<<"$lines" | sort -u)                  # the different partitions they landed in
  values=$(cut -f3 <<<"$lines" | xargs)                        # their values, in the order they arrived
  [ "$values" = "$expected" ] || fail "key $key arrived as '$values', expected '$expected'"
  [ "$(wc -l <<<"$partitions")" -eq 1 ] || fail "key $key was spread over $(xargs <<<"$partitions")"
  echo "ok  key $key -> ${partitions/Partition:/partition } only, in order: $values"
}
check_key order-A "A1 A2 A3"
check_key order-B "B1 B2"
check_key order-C "C1"

# 3. A misspelled topic is refused instead of being created (auto-create is off).
# The console producer exits with 0 even when sending fails, so check its error message and the topic list instead.
refused=$(echo hello | kafka_cli kafka-console-producer.sh --topic orders.event --command-property max.block.ms=5000 2>&1 || true)
grep -q "not present in metadata" <<<"$refused" || fail "sending to the misspelled topic orders.event did not fail"
topics_now=$(kafka_cli kafka-topics.sh --list)
grep -qx 'orders.event' <<<"$topics_now" && fail "Kafka created the misspelled topic orders.event"
echo "ok  misspelled topic orders.event refused, not created"

# 4. The address the services use (localhost:9092) is reachable from this machine
nc -z 127.0.0.1 9092 > /dev/null 2>&1 || fail "nothing listens on 127.0.0.1:9092"
echo "ok  127.0.0.1:9092 reachable"

echo "Smoke test passed"
