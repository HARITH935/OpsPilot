#!/bin/bash
# Creates every topic listed in topics.txt. Run by the one-shot "kafka-init" container once Kafka is healthy.
# Safe to run again: --if-not-exists skips topics that already exist.
# Kafka warns about "." in topic names (metric names turn "." into "_"); harmless, since no topic name uses "_".
set -euo pipefail

BOOTSTRAP="kafka:19092"   # Kafka's listener for other containers (see docker-compose.yml)
PARTITIONS=3              # Part B §B2.2; a message's key (the order id) decides its partition
REPLICATION=1             # one broker, so one copy of each message

# grep drops comment and blank lines; read -r reads one topic name per line
grep -Ev '^[[:space:]]*(#|$)' /scripts/topics.txt | while read -r topic; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$PARTITIONS" --replication-factor "$REPLICATION"
done

echo "Topics in Kafka now:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list --exclude-internal   # hide Kafka's own topics
