# deploy/docker

Local development stack (Docker Compose): **PostgreSQL**, **Kafka** and **Redis**, and, with the profile `apps`,
**the whole shop**: the four Java services as containers (step 3.2). No JDK needed for that.

## First-time setup
```bash
cd deploy/docker
cp .env.example .env          # then replace every change-me, e.g. with: openssl rand -hex 16
```
`.env` holds real passwords and is gitignored — never commit it. If you made your `.env` before a new variable was
added to `.env.example` (e.g. `REDIS_PASSWORD`), add it by hand: `docker compose up` names any that are missing.

## Start / stop (from the repo root)
Two ways to use it:
- **Infrastructure only** (no profile): Postgres, Kafka, Redis. You run the services yourself (`java -jar …`, your IDE,
  or the [end-to-end tests](../../e2e/README.md)).
- **The whole shop** (`--profile apps`): the same plus the four services in containers, gateway on `localhost:8080`.

Use one or the other. If a service runs both on your machine and in Docker, the two copies join the same Kafka
consumer group and split the messages between them.

```bash
# Infrastructure only
docker compose -f deploy/docker/docker-compose.yml up -d      # start
docker compose -f deploy/docker/docker-compose.yml ps -a      # status: wait for "healthy"; kafka-init ends as "Exited (0)" = done
docker compose -f deploy/docker/docker-compose.yml down       # stop, keep data
docker compose -f deploy/docker/docker-compose.yml down -v    # stop AND delete all data

# The whole shop: builds the four images (from services/Dockerfile) and waits until everything is healthy
docker compose -f deploy/docker/docker-compose.yml --profile apps up -d --build --wait
docker compose -f deploy/docker/docker-compose.yml --profile apps ps -a
docker compose -f deploy/docker/docker-compose.yml --profile apps down   # stop everything, keep data
```
**Stop the shop with `--profile apps` too.** A plain `down` only knows the services without a profile: it removes
Postgres, Kafka and Redis and leaves the four services running without them.

## The shop (profile `apps`)
The four services run from images built by [`services/Dockerfile`](../../services/Dockerfile) (step 3.1,
[ADR 0008](../../docs/adr/0008-phase3-foundations.md)), tagged `opspilot/<service>:dev`. Compose **always builds them
and never downloads** (`pull_policy: build`): otherwise it first tries to pull `opspilot/<service>` from Docker Hub,
where anyone could publish an image under that name. When nothing changed, the build takes about 2 s.

| Service | On your machine | Inside the network | Memory limit |
|---|---|---|---|
| api-gateway | `localhost:8080` (the front door) | `api-gateway:8080` | 384 MB |
| order-service | `localhost:18081` (8081 is often taken by other dev tools) | `order-service:8081` | 512 MB |
| payment-service | `localhost:8082` | `payment-service:8082` | 512 MB |
| inventory-service | `localhost:8083` | `inventory-service:8083` | 512 MB |

All ports are published on `127.0.0.1` only. Inside the network the services reach the rest by name: `postgres:5432`,
`kafka:19092`, `redis:6379`. Passwords come from `.env`, as for the infrastructure; the gateway has the demo logins
(viewer / engineer / admin) on and makes a temporary JWT key at every start.

**Start order and health.** Each service is *healthy* once its `/actuator/health/readiness` answers UP (checked with
`curl` inside the container). The three services wait until Postgres (and Redis, for inventory) is healthy and
`kafka-init` has created the topics; the gateway waits until the three services are healthy. Healthy means *ready for
HTTP*, not that the Kafka listeners already have their partitions; normally that follows within a second.

**Locked down like in Kubernetes later:** read-only file system (only `/tmp` writable, in memory), no Linux
capabilities, no privilege escalation, user 10001. Docker restarts a service that stops on its own
(`restart: unless-stopped`), but not one that is only unhealthy; Kubernetes does that (Phase 4).

**Change a setting for one start** (values from your shell win over the file's defaults):
```bash
PAYMENT_FAILURE_RATE=1.0 docker compose -f deploy/docker/docker-compose.yml --profile apps up -d payment-service   # a provider that refuses everything
docker compose -f deploy/docker/docker-compose.yml --profile apps up -d payment-service                           # back to normal
```
Can be set this way: `PAYMENT_FAILURE_RATE` (0.0), `PAYMENT_LATENCY_MS` (100), `GATEWAY_CIRCUIT_OPEN_FOR` (30s).

**Good to know**
- `up` / `start` for one service also runs `kafka-init` again (about 10 s), because the service depends on it. To
  restart a single service quickly: `docker compose … --profile apps up -d --no-deps <service>`.
- `docker compose stop` ends Java with SIGTERM: it shuts down cleanly in under a second (exit code 143), and the Kafka
  consumers leave their group, so a restart gets its partitions at once.
- If your Mac sleeps in the middle of `up --wait`, it can report "unhealthy" and give up: the health checks' grace
  period runs out while everything is paused. Run it again. If everything feels slow after a long sleep, restart
  Docker Desktop.

**Measured** (2026-11-05, MacBook Air M2, Docker with 3.8 GB): from nothing (images deleted) to everything healthy
in 47 s, 29 s when the images exist; the first order through `:8080` `CONFIRMED` 3.2 s after the `POST`; about 1.6 GB
of memory for the whole shop (services 235–330 MB each); a service whose Java process ended on its own was started
again by Docker and healthy again within seconds.

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
One Redis 7 (`redis:7.4.12-alpine`) for the API gateway's **rate-limit counters** (`ratelimit:*`, step 2.9) and
inventory's **product cache** (`inventory:product:v1:*`, step 2.10). All of it is short-lived, so Redis keeps it **in memory only** (no snapshots, no
append-only file): a restart starts empty, and that is fine. Capped at 64 MB; when full, it drops the least recently
used keys (`allkeys-lru`). About 10 MB in use.

| Client | Connects to | Password |
|---|---|---|
| The gateway and inventory-service on your machine | `localhost:6379` | `REDIS_PASSWORD` from `.env` |
| Other containers in this compose file | `redis:6379` | the same |

The port is published on `127.0.0.1` only, and a password is required anyway (`NOAUTH Authentication required`
without it). Inside the container, `redis-cli` logs in by itself (`REDISCLI_AUTH`):
```bash
docker exec opspilot-redis redis-cli ping                                   # PONG
docker exec opspilot-redis redis-cli --scan --pattern 'ratelimit:*'         # the gateway's counters (SCAN never blocks Redis)
docker exec opspilot-redis redis-cli zcard 'ratelimit:orders:user:demo:admin'   # requests counted in the current window
docker exec opspilot-redis redis-cli --scan --pattern 'inventory:product:*'  # inventory's cached products
docker exec opspilot-redis redis-cli ttl 'inventory:product:v1:SKU-1'        # seconds until that copy expires
```
