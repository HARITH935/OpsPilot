# inventory-service (:8083)

Product catalogue, stock, and stock reservations for the order saga. It reacts to `OrderCreated` from Kafka by
reserving the stock and answering `StockReserved` / `StockRejected`. Next (Phase 2): commit / release on the payment
result, and a Redis cache for product reads.

## Run
Needs the local Postgres from `deploy/docker` running (see its README).
```bash
cd services
export DB_PASSWORD=$(grep '^INVENTORY_DB_PASSWORD=' ../deploy/docker/.env | cut -d= -f2)
./mvnw -pl inventory-service spring-boot:run   # starts on :8083
```
On startup Flyway applies any new `db/migration/V*.sql` files (the very first start also loads 10 demo products),
then Hibernate checks the entities match the tables. With Kafka running (also `deploy/docker`) it starts reading
`orders.events` where it stopped last time (its very first start reads the topic from the beginning).

## Test
```bash
cd services
./mvnw -pl inventory-service verify
```
Needs Docker running: the tests start their own throwaway Postgres, and the full-app tests a throwaway Kafka too
(Testcontainers), so they never touch the containers in `deploy/docker`.

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
- `POST` is meant for admins only. The API gateway enforces that from Phase 2 (step 4); this service has no login check.
- Errors use **RFC 9457 Problem Details** (`application/problem+json`), the same format as order-service:
  `{"title":"Bad Request","status":400,"errors":{"price":"must be greater than 0"}}`

## Events
| Direction | Topic | Event | What inventory does |
|---|---|---|---|
| in | `orders.events` | `OrderCreated` | reserves the stock (all or nothing) and answers ↓ |
| out | `inventory.events` | `StockReserved` `{orderId, items[sku, qty, unitPrice], totalAmount}` | stock held, order priced |
| out | `inventory.events` | `StockRejected` `{orderId, sku, reason}` | nothing held: `UNKNOWN_PRODUCT` / `OUT_OF_STOCK` |

`OrderEventsListener` (consumer group `inventory-service`) calls `ReservationService.reserveForOrder`, which does
three things in **one transaction**: record the event id in `processed_events`, reserve, write the answer to the
outbox. So a redelivered event is skipped ("already handled, skipping the duplicate"), and a stock conflict retries
all three together. Other order events are ignored until step 2.5. A message that still fails after 3 retries (or
that isn't a readable event) is moved to `orders.events.dlq` and logged as `Moving message ... to orders.events.dlq`;
see [`common-messaging`](../common-messaging/README.md).
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
| `commit(orderId)` | `PaymentCompleted` | The held units leave the warehouse: `stock -= qty`, `reserved -= qty` |
| `release(orderId)` | `PaymentFailed` (or the order is cancelled) | The held units are on sale again: `reserved -= qty` |

- Each reservation row moves once: `HELD` → `COMMITTED` or `HELD` → `RELEASED`. Repeating `commit` / `release` changes nothing (returns 0); reserving the same order twice is refused.
- **Two orders, same product, same moment:** the product's `version` column (optimistic locking) makes the slower update fail instead of overwriting the other. Spring Framework 7's built-in `@Retryable` then runs the method again with fresh data: up to 5 retries, about 10 → 160 ms apart with random jitter. `RetryConfig` logs every failed attempt.
- **No deadlocks:** `hibernate.order_updates=true` sends UPDATEs sorted by SKU, so every transaction locks products in the same order.
- Proven by `ReservationIntegrationTest`: 20 orders grab the last 5 units at the same moment → exactly 5 held, 15 rejected, stock never below 0.

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

## Database
Migrations in `src/main/resources/db/migration/`:
- `V1__init.sql`: `products` with CHECK rules (price > 0, stock ≥ 0, 0 ≤ reserved ≤ stock), `outbox`, `processed_events`
- `V2__seed_products.sql`: 10 demo products `SKU-1` … `SKU-10` (`SKU-10` has only 3 in stock, for out-of-stock demos)
- `V3__create_reservations.sql`: `reservations` (status `HELD` / `COMMITTED` / `RELEASED`, one row per order + product, foreign key to `products`)

Never edit a merged migration — add the next `V<n>__...sql`.

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = the Kafka listener (`OrderEventsListener`), `config` = Spring config (`MessagingConfig` turns on the outbox
and the listener's shared settings), `exception` = error handling.
