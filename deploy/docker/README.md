# deploy/docker

Local development stack (Docker Compose). Grows through Phases 1–3; today it runs **PostgreSQL** and **Kafka**.

## First-time setup
```bash
cd deploy/docker
cp .env.example .env          # then replace every change-me, e.g. with: openssl rand -hex 16
```
`.env` holds real passwords and is gitignored — never commit it.

## Start / stop (from the repo root)
```bash
docker compose -f deploy/docker/docker-compose.yml up -d      # start
docker compose -f deploy/docker/docker-compose.yml ps -a      # status: wait for "healthy"; kafka-init ends as "Exited (0)" = done
docker compose -f deploy/docker/docker-compose.yml down       # stop, keep data
docker compose -f deploy/docker/docker-compose.yml down -v    # stop AND delete all data
```

## PostgreSQL
One container, **one database + one login per service** (database-per-service):

| Database | User | Password env var | Used by |
|---|---|---|---|
| `orders` | `orders_user` | `ORDERS_DB_PASSWORD` | order-service |
| `inventory` | `inventory_user` | `INVENTORY_DB_PASSWORD` | inventory-service |
| `payments` | `payments_user` | `PAYMENTS_DB_PASSWORD` | payment-service |

Each user can connect **only** to its own database. The databases are created by
`postgres/init/01-create-service-databases.sh`, which Postgres runs **only on first start** (empty volume).
If you change passwords in `.env` later, run `down -v` and `up -d` again to re-create them.

Open a SQL shell: `docker exec -it opspilot-postgres psql -U postgres`

## Kafka
One Kafka node in **KRaft** mode (broker and controller in one container, no ZooKeeper), image `apache/kafka:4.2.2`,
Java heap capped at 512 MB (about 320 MB in use when idle).

| Client | Connects to |
|---|---|
| The services on your machine (`java -jar …`) | `localhost:9092` |
| Other containers in this compose file | `kafka:19092` |

There is no password or TLS locally, so the port is published on `127.0.0.1` only (not reachable from your network).

**Topics** are listed in [`kafka/topics.txt`](kafka/topics.txt), 3 partitions each. The one-shot `kafka-init` container
creates them once Kafka is healthy, on every `up`, and skips topics that already exist. Kafka's automatic topic
creation is **off**, so a misspelled topic name fails instead of quietly creating a new topic. To add a topic, add a
line to `topics.txt` and run `up -d` again. (`kafka-init` warns about `.` in topic names; that is harmless here,
because no topic name uses `_`.)

**Check it:** `deploy/docker/kafka/smoke-test.sh` verifies that every topic exists with 3 partitions, that messages
with the same key stay in one partition and in order, and that a misspelled topic is refused. It writes only to a
throwaway topic, never to the real ones.

Kafka's command-line tools live inside the container:
```bash
# Describe a topic (partitions, leader)
docker exec opspilot-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic orders.events
# Read every message in a topic, with its key (Ctrl+C to stop)
docker exec -it opspilot-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders.events --from-beginning --formatter-property print.key=true
```
