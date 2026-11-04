# payment-service (:8082)

Takes the money for an order through a **simulated payment provider**. Its failure rate and latency come from
`PAYMENT_FAILURE_RATE` / `PAYMENT_LATENCY_MS`, so chaos scenarios can break payments on purpose. In the saga it
charges each order once inventory has reserved and priced it (`StockReserved`), and answers `PaymentCompleted` /
`PaymentFailed`. If the charge came too late (the saga timeout had already cancelled the order), order-service asks
for a refund (`RefundRequested`), and payment gives the money back and answers `PaymentRefunded`
([ADR 0006](../../docs/adr/0006-refund-late-payments.md)). Every call to the provider is protected by a timeout,
retries, a circuit breaker and a bulkhead ([below](#protection-around-the-provider)).

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
With Kafka running (also `deploy/docker`) it reads `inventory.events` and `orders.events` where it stopped last time
(the very first read of a topic starts at its beginning).

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
- `status` is `COMPLETED`, `FAILED` or `REFUNDED`; `failureReason` is set exactly when it failed, `refundedAt` exactly
  when it was refunded.
- Payments are created only by the order saga (Kafka, Phase 2), so `POST /api/payments` answers **405 Method Not Allowed**.
- Errors use **RFC 9457 Problem Details** (`application/problem+json`), the same format as the other services.

## Events
| Direction | Topic | Event | What payment does |
|---|---|---|---|
| in | `inventory.events` | `StockReserved` `{orderId, items, totalAmount}` | charges exactly `totalAmount` (inventory prices orders, [ADR 0004](../../docs/adr/0004-orders-priced-by-inventory.md)) and answers ↓ |
| in | `inventory.events` | `StockRejected` / `StockReleased` | nothing: there is no money to take |
| out | `payments.events` | `PaymentCompleted` `{orderId, paymentId, amount}` | the provider took the money → order confirms, inventory commits the stock |
| out | `payments.events` | `PaymentFailed` `{orderId, paymentId, amount, reason}` | the charge failed (`PROVIDER_ERROR` / `PROVIDER_TIMEOUT` / `PROVIDER_UNAVAILABLE`, see below) → order cancels, inventory releases the stock |
| in | `orders.events` | `RefundRequested` `{orderId, paymentId}` | the payment completed after its order was cancelled: refunds it and answers ↓ |
| out | `payments.events` | `PaymentRefunded` `{orderId, paymentId, amount}` | the money went back (nobody has to react yet) |
| in | `orders.events` | `OrderCreated` / `OrderConfirmed` / `OrderCancelled` | nothing: charging starts from `StockReserved` |

`InventoryEventsListener` calls `charge`; `OrderEventsListener` calls `refund`. Both are in consumer group
`payment-service` (the other services read the same topics in their own groups). Failures are retried 3 times, then
the message goes to `<topic>.dlq` (see [`common-messaging`](../common-messaging/README.md)).
```bash
docker exec -it opspilot-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payments.events --from-beginning --formatter-property print.key=true
```

## Charging (no HTTP endpoint)
`PaymentService.charge(eventId, orderId, amount)`, for the `StockReserved` event `eventId`:
1. The order already has a payment (the same event delivered again) → return it; the provider is **not** charged again.
2. Otherwise call the provider **outside** any database transaction, so a slow provider can't hold database connections.
3. In one short transaction: mark the event in `processed_events`, store the outcome (`COMPLETED`, or `FAILED` with
   the reason the provider call gave) and write the answer event to the outbox. All three are saved, or none.

The unique `order_id` keeps it at one stored payment per order, even if two charges race.

## Refunds (no HTTP endpoint)
`PaymentService.refund(eventId, paymentId)`, for the `RefundRequested` event `eventId`. It works like a charge:
1. The payment is already `REFUNDED` (the same request delivered again) → return it; the provider is **not** called again.
   A `FAILED` payment is never refunded (no money was taken).
2. Otherwise the provider gives back the amount **stored with the payment**, outside any database transaction.
3. In one short transaction: mark the event, save the payment as `REFUNDED` (with `refundedAt`) and write
   `PaymentRefunded` to the outbox. The payment's `@Version` lets only one of two racing refunds be saved.

If the provider refuses, nothing is saved: Kafka retries the request after 0.5, 1 and 2 s, then parks it in
`orders.events.dlq` for a human. `OrderEventsIntegrationTest` checks this (4 tries, then the dead-letter topic).

## Simulated provider
Every charge **and every refund** waits `PAYMENT_LATENCY_MS`, then fails when a random number in [0, 1) is below
`PAYMENT_FAILURE_RATE` (a real provider's outage would hit refunds too).
`SimulatedPaymentProviderTest` checks the rate: 10,000 charges at 30% → 2,924 failures (a seeded random, so the same on every run).

## Protection around the provider
`ResilientPaymentProvider` wraps the simulated provider (a **decorator**: the same `PaymentProvider` interface, extra
behaviour), so `PaymentService` doesn't change. Every charge and every refund goes through, outside in:

```
CircuitBreaker ( Retry ( Timeout ( Bulkhead ( provider ) ) ) )
```

| Part | What it does | When it fails: `failureReason` |
|---|---|---|
| **Bulkhead** | at most 5 provider calls at once; a 6th waits up to 1 s | `PROVIDER_UNAVAILABLE` (too busy) |
| **Timeout** | an answer within 2 s (`PAYMENT_PROVIDER_TIMEOUT`); the call is interrupted | `PROVIDER_TIMEOUT`, **not retried** |
| **Retry** | after `PROVIDER_ERROR` only: 2 retries, about 200 → 400 ms, each ± 50% at random | `PROVIDER_ERROR` once the retries failed too |
| **Circuit breaker** | 50% of the last 20 **payments** failed → the provider isn't called for 30 s (`PAYMENT_PROVIDER_OPEN_FOR`), then 3 test payments | `PROVIDER_UNAVAILABLE` at once |

- **Why retry only `PROVIDER_ERROR`:** it means "refused, nothing was taken", so trying again can't charge twice. A
  **timeout** means "unknown": with a real provider the money may already be gone, so the next step would be asking
  the provider (by the order id), never charging again.
- **Why the breaker sits outside the retries** ([ADR 0007](../../docs/adr/0007-provider-breaker-judges-payments.md)):
  it judges payments, not attempts. A payment that worked on its second try is a success. With every attempt
  counted, chaos scenario C12 (50% errors) opened the breaker at once, and only 6 or 21 of 36 payments got through.
- **Why the bulkhead is innermost:** it counts the calls that really run, including one the timeout already gave up
  on, so a hanging provider can't tie up more than 5 threads. Today at most 2 provider calls run at once (one charge
  listener and one refund listener, one thread each), so it fills only once listeners get more threads.
- **Open circuit → the payment fails at once** (`PaymentFailed`, reason `PROVIDER_UNAVAILABLE`). The order is cancelled
  and its stock released within about a second, instead of every payment waiting for a broken provider.
- **Refunds** get the same protection; one that still fails goes to the Kafka retries and then `orders.events.dlq`,
  as before.
- **A bug** (any other exception) is not turned into a failed payment: it goes to the Kafka retries and the DLQ.
- **Logs:** `INFO … Payment provider: retry 1 in 167 ms (…)`, `WARN … Circuit breaker payment-provider: CLOSED → OPEN …`.
- **Metrics**, all `name="payment-provider"`: `resilience4j_circuitbreaker_state{state}`, `resilience4j_retry_calls_total{kind}`,
  `resilience4j_timelimiter_calls_total{kind="successful|failed|timeout"}`, `resilience4j_bulkhead_available_concurrent_calls`.

**From the real run (2026-11-04, all four services, 40 orders through the gateway each):**

| Provider | Orders | Payments | Breaker |
|---|---|---|---|
| `PAYMENT_FAILURE_RATE=0.5` (C12), breaker counting attempts (first version) | 40 | 6 resp. 21 of 36 completed, the rest `PROVIDER_UNAVAILABLE` | opened in both runs |
| `PAYMENT_FAILURE_RATE=0.5` (C12), breaker counting payments | **37 confirmed**, 3 cancelled | 37 completed (24 retries), 3 `PROVIDER_ERROR` | stayed closed |
| `PAYMENT_FAILURE_RATE=1.0` (30 orders) | 30 cancelled, the slowest 14 s after the order | 20 × `PROVIDER_ERROR` (about 1 s each: 3 tries), then 10 × `PROVIDER_UNAVAILABLE` 10 ms apart | opened after 20 payments |
| `PAYMENT_LATENCY_MS=3000` (3 orders) | 3 cancelled | 3 × `PROVIDER_TIMEOUT`, 2.0–2.1 s apart, no retries | closed |

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
| `PAYMENT_FAILURE_RATE` | `0.0` | Share of charges and refunds that fail, `0.0` – `1.0` (chaos scenario C12 uses `0.5`) |
| `PAYMENT_LATENCY_MS` | `100` | How long every charge and refund takes, in milliseconds (0 or more) |
| `PAYMENT_PROVIDER_TIMEOUT` | `2s` | Longest wait for the provider's answer (then `PROVIDER_TIMEOUT`); 10 ms – 1 min |
| `PAYMENT_PROVIDER_OPEN_FOR` | `30s` | How long an open circuit breaker skips the provider before testing it again |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka address (`kafka:19092` from inside Docker) |

## Database
Migrations in `src/main/resources/db/migration/`:
- `V1__init.sql`: `payments` (one per order; CHECK rules: amount > 0, known status, a reason exactly when failed), `outbox`, `processed_events`
- `V2__allow_refunds.sql`: the status `REFUNDED` and `refunded_at` (CHECK: set exactly when refunded)

Never edit a merged migration — add the next `V<n>__...sql`.

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = the Kafka listeners (`InventoryEventsListener`, `OrderEventsListener`), `config` = Spring config (provider settings,
`ProviderResilienceProperties` + `ProviderResilienceConfig` for the protection, `MessagingConfig` for the outbox and Kafka),
`exception` = error handling (`PaymentProviderException` carries the failure reason).
The provider (`PaymentProvider`, `SimulatedPaymentProvider`, and `ResilientPaymentProvider` around it) lives in `service`.
