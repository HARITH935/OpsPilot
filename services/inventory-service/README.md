# inventory-service (:8083)

Product catalogue, stock, and stock reservations for the order saga. It reacts to `OrderCreated` from Kafka by
reserving the stock and answering `StockReserved` / `StockRejected`, then to the payment result: commit the held stock
(paid) or release it and answer `StockReleased` (not paid, or the order was cancelled, e.g. by the saga timeout).
If an order is paid only after the timeout cancelled it, the stock that payment committed comes back too ([ADR 0006](../../docs/adr/0006-refund-late-payments.md)).
`GET /api/products/{sku}` is answered from a Redis copy when there is one ([product cache](#product-cache)).

## Run
Needs the local Postgres from `deploy/docker` running (see its README); Kafka and Redis from there too for the full picture.
```bash
cd services
export DB_PASSWORD=$(grep '^INVENTORY_DB_PASSWORD=' ../deploy/docker/.env | cut -d= -f2)
export REDIS_PASSWORD=$(grep '^REDIS_PASSWORD=' ../deploy/docker/.env | cut -d= -f2-)   # the product cache
./mvnw -pl inventory-service spring-boot:run   # starts on :8083
```
Without Redis (or without `REDIS_PASSWORD`) it still works: every product is then read from Postgres, with a WARN.
On startup Flyway applies any new `db/migration/V*.sql` files (the very first start also loads 10 demo products),
then Hibernate checks the entities match the tables. With Kafka running (also `deploy/docker`) it starts reading
`orders.events` and `payments.events` where it stopped last time (its very first start reads the topic from the beginning).

## Test
```bash
cd services
./mvnw -pl inventory-service verify
```
Needs Docker running: the tests start their own throwaway Postgres, and the full-app tests a throwaway Kafka and Redis
too (Testcontainers), so they never touch the containers in `deploy/docker`.

## API
| Method + path | Success | Errors |
|---|---|---|
| `GET /api/products?page=&size=` | **200** `{content, page, size, totalElements, totalPages}`, sorted by SKU | 400 page < 0 · size not 1–100 |
| `GET /api/products/{sku}` | **200** the product | 400 malformed SKU · 404 unknown SKU |
| `POST /api/products` | **201** the product + `Location: /api/products/{sku}` | 400 invalid body · 409 SKU already exists |

```bash
curl 'localhost:8083/api/products?page=0&size=20'
curl localhost:8083/api/products/SKU-1
curl -i -X POST localhost:8083/api/products -H 'Content-Type: application/json' \
     -d '{"sku":"SKU-11","name":"Mouse Pad","price":9.99,"stock":200}'
```

A product: `{"sku":"SKU-1","name":"Mechanical Keyboard","price":89.99,"stock":500,"reserved":0,"available":500}`
- `stock` = units on hand · `reserved` = units held for orders not paid yet (step 1.6) · `available` = `stock - reserved`.
- **SKU:** 1–64 characters of `A-Z`, `0-9` and `-`, because it is part of the URL. The list is sorted as text, so `SKU-10` comes before `SKU-2`.
- **price** greater than 0 with at most 2 decimals · **stock** 0 – 1,000,000.
- **201 Created**, not 202 like orders: the product is complete once stored, nothing else runs afterwards.
- **409** also when two requests create the same SKU at the same moment: the primary key lets exactly one win.
- `POST` is for admins only: the [API gateway](../api-gateway/README.md) lets it through only with an ADMIN token (step 2.8). This service itself has no login check, so in Kubernetes only the gateway can reach it (Phase 4).
- Errors use **RFC 9457 Problem Details** (`application/problem+json`), the same format as order-service:
  `{"title":"Bad Request","status":400,"errors":{"price":"must be greater than 0"}}`

## Events
| Direction | Topic | Event | What inventory does |
|---|---|---|---|
| in | `orders.events` | `OrderCreated` | reserves the stock (all or nothing) and answers ↓ |
| out | `inventory.events` | `StockReserved` `{orderId, items[sku, qty, unitPrice], totalAmount}` | stock held, order priced |
| out | `inventory.events` | `StockRejected` `{orderId, sku, reason}` | nothing held: `UNKNOWN_PRODUCT` / `OUT_OF_STOCK` |
| in | `payments.events` | `PaymentCompleted` | commits the held stock (it leaves the warehouse); announces nothing |
| in | `payments.events` | `PaymentFailed` | releases the held stock (the saga's **compensation**) and answers ↓ |
| in | `orders.events` | `OrderCancelled` `{orderId, reason}` | any reason: releases whatever is still held, returns whatever a late payment already committed, and answers ↓. This frees the stock of an order the saga timeout cancelled; for the other reasons nothing is usually left |
| out | `inventory.events` | `StockReleased` `{orderId}` | the units are on sale again (only sent if something actually changed) |
| in | `orders.events` / `payments.events` | `OrderConfirmed`, `RefundRequested` / `PaymentRefunded` | nothing: the stock was already settled |

`OrderEventsListener` (consumer group `inventory-service`) calls `ReservationService.reserveForOrder`, which does
three things in **one transaction**: record the event id in `processed_events`, reserve, write the answer to the
outbox. So a redelivered event is skipped ("already handled, skipping the duplicate"), and a stock conflict retries
all three together. `PaymentEventsListener` (same group) works the same way through `onPaymentCompleted` /
`onPaymentFailed`, and `OrderEventsListener` through `onOrderCancelled`: duplicate mark + commit or release
(+ `StockReleased`) in one transaction. Whichever of `PaymentFailed` / `OrderCancelled` comes first releases the
stock; the other finds nothing held, so `StockReleased` is sent once.
**A payment that came after the saga timeout** reaches inventory on a different topic from the order's
`OrderCancelled`, so either can come first. Cancel first: the stock is released, and the late `PaymentCompleted` then
finds nothing held. Payment first: the stock is committed (it leaves the warehouse), and `OrderCancelled` then puts it
back (`stock += qty`, reservation `RETURNED`). Both ways the units end up on sale again.
A message that still fails after 3 retries (or that isn't a readable event) is moved to `<topic>.dlq` and
logged as `Moving message ... to orders.events.dlq`; see [`common-messaging`](../common-messaging/README.md).
```bash
docker exec -it opspilot-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic inventory.events --from-beginning --formatter-property print.key=true
docker exec opspilot-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group inventory-service   # how far inventory has read (LAG 0 = everything)
```

## Stock reservations (no HTTP endpoint)
`ReservationService` holds stock while an order is being paid; the saga's Kafka events call it.

| Method | Called on (Phase 2) | Effect |
|---|---|---|
| `reserve(orderId, items)` — via `reserveForOrder(eventId, OrderCreated)` | `OrderCreated` | **All or nothing:** every item held (`reserved += qty`) → `Reserved` with unit prices + total (inventory prices orders, [ADR 0004](../../docs/adr/0004-orders-priced-by-inventory.md)) · or nothing held → `Rejected` (`UNKNOWN_PRODUCT` / `OUT_OF_STOCK`) |
| `commit(orderId)` — via `onPaymentCompleted(eventId, orderId)` | `PaymentCompleted` | The held units leave the warehouse: `stock -= qty`, `reserved -= qty` |
| `release(orderId)` — via `onPaymentFailed` / `onOrderCancelled(eventId, orderId)` | `PaymentFailed`, `OrderCancelled` (e.g. saga timeout) | The held units are on sale again: `reserved -= qty`; `StockReleased` is announced |
| via `onOrderCancelled(eventId, orderId)` only | `OrderCancelled` after a late `PaymentCompleted` | The committed units come back: `stock += qty`; `StockReleased` is announced |

- Each reservation row moves once: `HELD` → `COMMITTED` or `HELD` → `RELEASED`. The one exception is `COMMITTED` → `RETURNED`, for an order paid only after the saga timeout cancelled it. Repeating `commit` / `release` changes nothing (returns 0); reserving the same order twice is refused.
- **Two orders, same product, same moment:** the product's `version` column (optimistic locking) makes the slower update fail instead of overwriting the other. Spring Framework 7's built-in `@Retryable` then runs the method again with fresh data: up to 5 retries, about 10 → 160 ms apart with random jitter. `RetryConfig` logs every failed attempt.
- **No deadlocks:** `hibernate.order_updates=true` sends UPDATEs sorted by SKU, so every transaction locks products in the same order.
- Proven by `ReservationIntegrationTest`: 20 orders grab the last 5 units at the same moment → exactly 5 held, 15 rejected, stock never below 0.

## Product cache
`GET /api/products/{sku}` is the most frequent read (product pages), so inventory keeps a copy of each product in
Redis: **cache-aside**. `ProductService` asks `ProductCache` first:
- **hit:** the copy is there → answered from Redis, without a database connection
- **miss:** not there → read from Postgres, and the answer is kept in Redis for **60 s** (`PRODUCT_CACHE_TTL`)

Key `inventory:product:v1:<sku>`, value = the same JSON the API returns. The `v1` means: if the answer ever changes
shape, the new version reads new keys and never an old copy. The product list and unknown SKUs (404) are not cached.

**When stock changes, the copy goes.** `ProductCacheEvictor` is a JPA entity listener on `Product`: Hibernate calls it
on every INSERT, UPDATE or DELETE of a product row (reserve, commit, release, return, a new product). It deletes that
product's copy **after the transaction commits**. Not earlier: until the commit, the database still shows the old
values, and a read in between would put them straight back. A rolled-back change deletes nothing.
`ProductCacheIntegrationTest` checks exactly that race: a read during an uncommitted change caches the old values, and
the commit removes them.

**Known limits**, each lasting at most the TTL (60 s):
- A read that misses, then loses the race to a change (the change commits and deletes the copy before the read puts its
  older answer), leaves an out-of-date copy. This is the classic cache-aside trade-off.
- Changes made with plain SQL (not through Hibernate) are not seen by the listener.
- A delete that fails because Redis is down leaves the copy until it expires.

**Fail-open:** if Redis can't be reached, or a copy can't be read, Postgres answers. The shop keeps working, and it
shows in the log (one WARN at most every 30 s, an INFO when Redis is back) and in `cache_errors_total`. While Redis is
disconnected, commands are refused at once instead of waiting for the 100 ms timeout (`RedisConfig`). Redis down
doesn't make inventory unhealthy: its health check ignores Redis on purpose.

**Metrics** (at `/actuator/prometheus`): `cache_requests_total{cache="products",result="hit|miss"}`,
`cache_errors_total{cache="products",operation="read|write|evict"}`. Hit ratio = hits / (hits + misses).

**From the real run (2026-11-04, MacBook Air M2, all through the local stack):**

| | Cache on | Cache off (`PRODUCT_CACHE_ENABLED=false`) |
|---|---|---|
| 2000 reads one at a time, direct to :8083: median / p95 | 0.40–0.60 ms / 1.0–1.7 ms | 0.48–0.74 ms / 0.7–1.4 ms |
| Database connections taken for 2200 reads | **6** (1 miss + background jobs) | **2203** |

- Locally a single read is **not** faster: a primary-key lookup in a small local Postgres is about as quick as a trip
  to Redis, and the run-to-run noise is as big as the difference. What the cache saves is **database work**: about
  2200 fewer queries and connections. That matters once Postgres is busy, remote, or short of connections.
- 10 Locust shoppers through the gateway for 30 s: hit ratio **72.5%** (90 hits, 34 misses). Each order clears the copies
  of its products twice (reserve, then commit), so busy products miss more often.
- An order for SKU-1: its copy was gone about 100 ms later; the next read showed the new stock.
- `docker stop` Redis: 20 reads all 200 in 0.34 s, readiness `UP`, one WARN, errors counted. `docker start`: copies
  kept again about 1 s later.

```bash
docker exec opspilot-redis redis-cli get inventory:product:v1:SKU-1   # the copy (while it exists)
docker exec opspilot-redis redis-cli ttl inventory:product:v1:SKU-1   # seconds it has left
curl -s localhost:8083/actuator/prometheus | grep ^cache_
```

## Operational endpoints
| Path | Purpose |
|---|---|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/liveness` | "Is the process alive?" — Kubernetes restarts the pod if this fails |
| `GET /actuator/health/readiness` | "Can it take traffic?" — Kubernetes stops sending requests if this fails |
| `GET /actuator/prometheus` | Metrics for Prometheus |

## Configuration
| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8083` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory` | JDBC URL of the inventory database |
| `DB_USERNAME` | `inventory_user` | Database login |
| `DB_PASSWORD` | — (required) | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka address (`kafka:19092` from inside Docker) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis for the product cache |
| `REDIS_PASSWORD` | empty | Redis password (`REDIS_PASSWORD` in `deploy/docker/.env`). Wrong or missing → no caching, Postgres answers |
| `PRODUCT_CACHE_ENABLED` | `true` | `false`: never use Redis, read every product from Postgres |
| `PRODUCT_CACHE_TTL` | `60s` | How long a copy is kept (1 s … 1 h); also the longest a copy can be out of date. A wrong value stops the start |

## Database
Migrations in `src/main/resources/db/migration/`:
- `V1__init.sql`: `products` with CHECK rules (price > 0, stock ≥ 0, 0 ≤ reserved ≤ stock), `outbox`, `processed_events`
- `V2__seed_products.sql`: 10 demo products `SKU-1` … `SKU-10` (`SKU-10` has only 3 in stock, for out-of-stock demos)
- `V3__create_reservations.sql`: `reservations` (status `HELD` / `COMMITTED` / `RELEASED`, one row per order + product, foreign key to `products`)
- `V4__add_returned_reservations.sql`: the status `RETURNED` (committed stock of an order cancelled after all, ADR 0006)

Never edit a merged migration — add the next `V<n>__...sql`.

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = the Kafka listeners (`OrderEventsListener`, `PaymentEventsListener`), `config` = Spring config (`MessagingConfig` turns on the outbox
and the listener's shared settings; `RedisConfig` + `ProductCacheProperties` for the cache), `exception` = error handling.
`service` also holds `ProductCache` (the Redis copies) and `ProductCacheEvictor` (deletes a copy when its product changes).
