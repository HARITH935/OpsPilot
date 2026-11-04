# e2e

**End-to-end tests:** the whole shop as a black box. An order goes in at the gateway (:8080) and the tests watch how
it ends across order-service, inventory-service, payment-service, Kafka, Postgres and Redis. Each service also has
its own tests (`./mvnw verify`); these check that they **work together**.

The suite starts the four jars itself, with the settings each test needs, and stops them at the end. Only the Python
standard library is used (`urllib` for HTTP, Kafka's own command-line tools in its container for the Kafka checks),
plus pytest and ruff for development.

## Run
```bash
docker compose -f deploy/docker/docker-compose.yml up -d    # Postgres, Kafka, Redis (from the repo root)
(cd services && ./mvnw -DskipTests package)                 # the four jars (stop the services first if they run)
cd e2e && uv sync
JAVA_HOME=/opt/homebrew/opt/openjdk@21 uv run pytest        # any JDK 21: the suite checks the version
```
About 1 minute. The suite refuses to start (exit code 2) with a clear reason if a container isn't healthy, a jar is
missing, Java is older than 21, or a port it needs (8080, 18081, 8082, 8083) is already in use. Stop your own
running services first. order-service runs on **18081** here (`E2E_ORDER_PORT`), because 8081 is often taken by other
dev tools.

Each service writes its log to `e2e/logs/<service>.log` (new on every start; not in Git). After a failure, look there.

## What is tested
| Test | What happens | What it checks |
|---|---|---|
| `test_saga.py` happy path | 2 × SKU-1 | `CONFIRMED`, total = 2 × price (priced by inventory), one `COMPLETED` payment of that amount, stock − 2 and nothing still held |
| `test_saga.py` out of stock | one desk mat (SKU-10) more than is left | `CANCELLED OUT_OF_STOCK`, **no payment**, stock unchanged |
| `test_saga.py` payment failure | payment-service restarted with `PAYMENT_FAILURE_RATE=1.0`, then 1 × SKU-2 | `CANCELLED PAYMENT_FAILED`, one `FAILED` payment (`PROVIDER_ERROR`, after its retries), stock back to exactly where it was (the compensation) |
| `test_duplicates.py` | a real `StockReserved` from `inventory.events`, sent again byte for byte (same key, same `eventId`) | payment-service logs "already has a COMPLETED payment; not charging again", still one payment, order and stock unchanged |
| `test_resilience.py` | inventory-service stopped, product reads; then started again | first 503 "can't be reached" (after retries), then the circuit opens: 503 + `Retry-After` in under 0.1 s; once inventory is back, test reads close it again |
| `test_rate_limit.py` | 15 product reads without a token, as fast as possible | 10 × 200, 5 × 429 with `Retry-After` ≥ 1 and `X-RateLimit-Remaining: 0`; `gateway_ratelimit_rejected_total` up by exactly 5 |

A few things the tests take care of:
- **Every test leaves the system as it found it.** payment-service is restarted with a healthy provider, inventory
  is started again, and the rate-limit test waits until the budget is full again.
- **Waiting, not sleeping.** Results are polled (every 0.3 s, well under the gateway's limit) until they are there.
  The saga needs about 1–2 s.
- **Stock through the product cache.** A read that races a stock change can keep an old copy for up to the cache's
  60 s TTL (the known cache-aside limit, step 2.10). So stock checks allow 65 s; normally they pass in under 1 s.
- **Ready means Kafka-ready.** A service counts as started only when its readiness endpoint says UP *and* its Kafka
  listeners have their partitions. Otherwise the first event could wait.
- **The gateway's circuit opens for 5 s here, not 30** (`GATEWAY_CIRCUIT_OPEN_FOR`), to keep the run short.

## From the real runs (2026-11-04, MacBook Air M2)
- 3 runs in a row: **6 of 6 passed** each time, in 56–58 s.
- Testing the tests: each change below made its test fail.
  - payment-service with a provider that refuses everything → the happy path fails
  - rate limiting switched off → the rate-limit test fails
  - the circuit open for 60 s → the resilience test fails
  - payment-service's "already has a payment" check removed (a code change, rebuilt jar) → the duplicate test
    fails. Interesting: the second safety net, the event's duplicate mark (`processed_events`), still stopped a second
    *stored* payment, but only after the provider had been **charged a second time**. Counting stored payments alone
    wouldn't have noticed; the log check did.

## Develop
```bash
uv run ruff check . && uv run ruff format .
```
`stack.py` starts and stops the services (passwords from `deploy/docker/.env`, never printed); `shop.py` is the
client side: HTTP calls, waits, metrics, Kafka. In Phase 3 the stack will come from `docker compose`, and the same
tests run in CI.
