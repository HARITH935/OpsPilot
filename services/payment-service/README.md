# payment-service (:8082)

Takes the money for an order through a **simulated payment provider**. Its failure rate and latency come from
`PAYMENT_FAILURE_RATE` / `PAYMENT_LATENCY_MS`, so chaos scenarios can break payments on purpose. In the saga it
charges each order once inventory has reserved and priced it (`StockReserved`), and answers `PaymentCompleted` /
`PaymentFailed`.

## Run
Needs the local Postgres from `deploy/docker` running (see its README).
```bash
cd services
export DB_PASSWORD=$(grep '^PAYMENTS_DB_PASSWORD=' ../deploy/docker/.env | cut -d= -f2)
./mvnw -pl payment-service spring-boot:run                                                # starts on :8082
PAYMENT_FAILURE_RATE=0.5 PAYMENT_LATENCY_MS=500 ./mvnw -pl payment-service spring-boot:run   # a struggling provider
```
The startup log shows the provider settings: `Simulated payment provider: failure rate 0.5, latency 500 ms`.
An invalid value (e.g. `PAYMENT_FAILURE_RATE=2`) stops startup: `APPLICATION FAILED TO START … must be less than or equal to 1.0`.
With Kafka running (also `deploy/docker`) it reads `inventory.events` where it stopped last time (its very first
start reads the topic from the beginning).

## Test
```bash
cd services
./mvnw -pl payment-service verify
```
Needs Docker running: the tests start their own throwaway Postgres, and the full-app tests a throwaway Kafka too
(Testcontainers), so they never touch the containers in `deploy/docker`.

## API (read-only)
| Method + path | Success | Errors |
|---|---|---|
| `GET /api/payments?status=&page=&size=` | **200** `{content, page, size, totalElements, totalPages}`, newest first | 400 unknown status · page < 0 · size not 1–100 |
| `GET /api/payments/{id}` | **200** the payment | 400 not a UUID · 404 unknown payment |

```bash
curl 'localhost:8082/api/payments?status=FAILED&page=0&size=20'
curl localhost:8082/api/payments/<paymentId>
```

A payment: `{"id":"…","orderId":"…","amount":24.99,"status":"FAILED","failureReason":"PROVIDER_ERROR","createdAt":"…"}`
- `status` is `COMPLETED` or `FAILED`; `failureReason` is set exactly when it failed.
- Payments are created only by the order saga (Kafka, Phase 2), so `POST /api/payments` answers **405 Method Not Allowed**.
- Errors use **RFC 9457 Problem Details** (`application/problem+json`), the same format as the other services.

## Events
| Direction | Topic | Event | What payment does |
|---|---|---|---|
| in | `inventory.events` | `StockReserved` `{orderId, items, totalAmount}` | charges exactly `totalAmount` (inventory prices orders, [ADR 0004](../../docs/adr/0004-orders-priced-by-inventory.md)) and answers ↓ |
| in | `inventory.events` | `StockRejected` / `StockReleased` | nothing: there is no money to take |
| out | `payments.events` | `PaymentCompleted` `{orderId, paymentId, amount}` | the provider took the money → order confirms, inventory commits the stock |
| out | `payments.events` | `PaymentFailed` `{orderId, paymentId, amount, reason}` | the provider refused (`PROVIDER_ERROR`) → order cancels, inventory releases the stock |

`InventoryEventsListener` (consumer group `payment-service`; order-service reads the same topic in its own group)
calls `charge`. Failures are retried 3 times, then the message goes to `inventory.events.dlq` (see
[`common-messaging`](../common-messaging/README.md)).
```bash
docker exec -it opspilot-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payments.events --from-beginning --formatter-property print.key=true
```

## Charging (no HTTP endpoint)
`PaymentService.charge(eventId, orderId, amount)`, for the `StockReserved` event `eventId`:
1. The order already has a payment (the same event delivered again) → return it; the provider is **not** charged again.
2. Otherwise call the provider **outside** any database transaction, so a slow provider can't hold database connections.
3. In one short transaction: mark the event in `processed_events`, store the outcome (`COMPLETED`, or `FAILED` with
   `failureReason = PROVIDER_ERROR`) and write the answer event to the outbox. All three are saved, or none.

The unique `order_id` keeps it at one stored payment per order, even if two charges race.

## Simulated provider
Every charge waits `PAYMENT_LATENCY_MS`, then fails when a random number in [0, 1) is below `PAYMENT_FAILURE_RATE`.
`SimulatedPaymentProviderTest` checks the rate: 10,000 charges at 30% → 2,924 failures (a seeded random, so the same on every run).

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
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payments` | JDBC URL of the payments database |
| `DB_USERNAME` | `payments_user` | Database login |
| `DB_PASSWORD` | — (required) | Database password |
| `PAYMENT_FAILURE_RATE` | `0.0` | Share of charges that fail, `0.0` – `1.0` (chaos scenario C12 uses `0.5`) |
| `PAYMENT_LATENCY_MS` | `100` | How long every charge takes, in milliseconds (0 or more) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka address (`kafka:19092` from inside Docker) |

## Database
Migrations in `src/main/resources/db/migration/`:
- `V1__init.sql`: `payments` (one per order; CHECK rules: amount > 0, known status, a reason exactly when failed), `outbox`, `processed_events`

Never edit a merged migration — add the next `V<n>__...sql`.

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = the Kafka listener (`InventoryEventsListener`), `config` = Spring config (provider settings,
`MessagingConfig` for the outbox and Kafka), `exception` = error handling.
The provider (`PaymentProvider` + `SimulatedPaymentProvider`) lives in `service`.
