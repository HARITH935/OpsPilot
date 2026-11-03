# deploy/docker

Local development stack (Docker Compose). Grows through Phases 1–3; today it runs **PostgreSQL**, **Kafka** and **Redis**.

## First-time setup
```bash
cd deploy/docker
cp .env.example .env          # then replace every change-me, e.g. with: openssl rand -hex 16
```
`.env` holds real passwords and is gitignored — never commit it. If you made your `.env` before a new variable was
added to `.env.example` (e.g. `REDIS_PASSWORD`), add it by hand: `docker compose up` names any that are missing.

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

## Redis
One Redis 7 (`redis:7.4.12-alpine`) for the API gateway's **rate-limit counters** (step 2.9) and, from step 2.10,
inventory's product cache. All of it is short-lived, so Redis keeps it **in memory only** (no snapshots, no
append-only file): a restart starts empty, and that is fine. Capped at 64 MB; when full, it drops the least recently
used keys (`allkeys-lru`). About 10 MB in use.

| Client | Connects to | Password |
|---|---|---|
| The gateway on your machine | `localhost:6379` | `REDIS_PASSWORD` from `.env` |
| Other containers in this compose file | `redis:6379` | the same |

The port is published on `127.0.0.1` only, and a password is required anyway (`NOAUTH Authentication required`
without it). Inside the container, `redis-cli` logs in by itself (`REDISCLI_AUTH`):
```bash
docker exec opspilot-redis redis-cli ping                                   # PONG
docker exec opspilot-redis redis-cli --scan --pattern 'ratelimit:*'         # the gateway's counters (SCAN never blocks Redis)
docker exec opspilot-redis redis-cli zcard 'ratelimit:orders:user:demo:admin'   # requests counted in the current window
```
