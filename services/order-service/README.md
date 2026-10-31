# order-service (:8081)

Orders API, order saga participant, transactional outbox.

## Run
Needs the local Postgres from `deploy/docker` running (see its README).
```bash
cd services
export DB_PASSWORD=$(grep '^ORDERS_DB_PASSWORD=' ../deploy/docker/.env | cut -d= -f2)
./mvnw -pl order-service spring-boot:run             # starts on :8081
SERVER_PORT=18081 ./mvnw -pl order-service spring-boot:run   # if 8081 is busy
```
On startup Flyway applies any new `db/migration/V*.sql` files, then Hibernate checks the entities match the tables.

## Test
```bash
cd services
./mvnw -pl order-service verify
```
Needs Docker running: repository and health tests start their own throwaway Postgres (Testcontainers).

## API
| Method + path | Success | Errors |
|---|---|---|
| `POST /api/orders` | **202** `{orderId, status}` + `Location: /api/orders/{id}` | 400 invalid body or key · 422 key reused with a different body |
| `GET /api/orders/{id}` | **200** order with its items | 400 not a UUID · 404 unknown order |
| `GET /api/orders?status=&page=&size=` | **200** `{content, page, size, totalElements, totalPages}`, newest first | 400 unknown status · page < 0 · size not 1–100 |

```bash
curl -i -X POST localhost:8081/api/orders -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 3f6c2a1e-retry-safe' \
     -d '{"customerId":"c-1","items":[{"sku":"SKU-1","qty":2}]}'
curl localhost:8081/api/orders/<orderId>
curl 'localhost:8081/api/orders?status=PENDING&page=0&size=20'
```

### Idempotency-Key (optional header, 1–100 characters)
Send a unique key (e.g. a UUID) with each new order and **reuse it when retrying** the same request:

| Situation | Result |
|---|---|
| New key | Order created → 202 |
| Same key + same body (a retry) | The **original** order → 202 + `Idempotent-Replayed: true`; nothing new is created |
| Same key + different body | **422** — the key belongs to another request |
| Same key sent concurrently | Still exactly one order: the `UNIQUE (idempotency_key)` constraint decides the winner, the others get its order |

The body is compared through a SHA-256 fingerprint stored with the key (`request_fingerprint`, migration V2).
The list endpoint returns summaries without items, so it never runs one extra query per order (N+1).
- **202 Accepted**, not 201: the order is stored, but stock and payment are processed afterwards (saga, Phase 2).
- `totalAmount` / `unitPrice` are `null` until inventory prices the order during the saga ([ADR 0004](../../docs/adr/0004-orders-priced-by-inventory.md)).
- Errors use **RFC 9457 Problem Details** (`application/problem+json`). Validation errors list each bad field:
  `{"title":"Bad Request","status":400,"errors":{"items[0].qty":"must be greater than 0"}}`

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
| `SERVER_PORT` | `8081` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | JDBC URL of the orders database |
| `DB_USERNAME` | `orders_user` | Database login |
| `DB_PASSWORD` | — (required) | Database password |

## Database
Migrations in `src/main/resources/db/migration/`:
- `V1__init.sql`: `orders`, `order_items`, `outbox`, `processed_events` + indexes
- `V2__add_request_fingerprint.sql`: `orders.request_fingerprint` + CHECK (key and fingerprint always together)

Never edit a merged migration — add the next `V<n>__...sql`.

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = domain events / outbox, `config` = Spring config, `exception` = error handling.
