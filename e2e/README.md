# e2e

**End-to-end tests:** the whole shop as a black box. An order goes in at the gateway (:8080) and the tests watch how
it ends across order-service, inventory-service, payment-service, Kafka, Postgres and Redis. Each service also has
its own tests (`./mvnw verify`); these check that they **work together**.

The suite runs the shop with **Docker Compose**, from the same container images as `docker compose --profile apps up`
(step 3.2), built from the current code. So it tests what actually ships, and it needs nothing but Docker: no JDK, no
jar build. Only the Python standard library is used (`urllib` for HTTP, `subprocess` for Docker and Kafka's
command-line tools in its container), plus pytest and ruff for development.

## Run
```bash
cd e2e && uv sync
uv run pytest
```
About 1.5–2 minutes. Docker Desktop must be running and `deploy/docker/.env` must exist (copy `.env.example`).
The suite:
1. builds the four images from the current code (2 s when nothing changed; a changed service about 10–20 s) and
   starts them, plus Postgres, Kafka and Redis if they aren't running (`up --build --wait`);
2. waits until every service is healthy **and** its Kafka listeners have their partitions;
3. runs the 6 tests, recreating a container with other settings where a test needs it;
4. saves the four containers' logs to `e2e/logs/<service>.log` (not in Git) and removes the containers. Postgres,
   Kafka and Redis keep running.

It refuses to start (exit code 2) with a clear reason if Docker isn't running, `.env` is missing, or a port it needs
(8080, 18081, 8082, 8083) is in use: stop your own services, or the shop (`docker compose … --profile apps down`).
The passwords stay with Compose, which reads `.env` itself; the suite never reads them.

## What is tested
| Test | What happens | What it checks |
|---|---|---|
| `test_saga.py` happy path | 2 × SKU-1 | `CONFIRMED`, total = 2 × price (priced by inventory), one `COMPLETED` payment of that amount, stock − 2 and nothing still held |
| `test_saga.py` out of stock | one desk mat (SKU-10) more than is left | `CANCELLED OUT_OF_STOCK`, **no payment**, stock unchanged |
| `test_saga.py` payment failure | payment-service recreated with `PAYMENT_FAILURE_RATE=1.0`, then 1 × SKU-2 | `CANCELLED PAYMENT_FAILED`, one `FAILED` payment (`PROVIDER_ERROR`, after its retries), stock back to exactly where it was (the compensation) |
| `test_duplicates.py` | a real `StockReserved` from `inventory.events`, sent again byte for byte (same key, same `eventId`) | payment-service logs "already has a COMPLETED payment; not charging again", still one payment, order and stock unchanged |
| `test_resilience.py` | inventory-service stopped, product reads; then started again | first 503 "can't be reached" (after retries), then the circuit opens: 503 + `Retry-After` in under 0.1 s; once inventory is back, test reads close it again |
| `test_rate_limit.py` | 15 product reads without a token, as fast as possible | 10 × 200, 5 × 429 with `Retry-After` ≥ 1 and `X-RateLimit-Remaining: 0`; `gateway_ratelimit_rejected_total` up by exactly 5 |

A few things the tests take care of:
- **Every test leaves the system as it found it.** payment-service is recreated with a healthy provider, inventory
  is started again, and the rate-limit test waits until the budget is full again.
- **Fixed settings for the run** (`SETTINGS` in `stack.py`): a healthy provider, 100 ms provider latency, and the
  gateway's circuit open for **5 s, not 30**, to keep the run short. A value left in your shell can't change them.
- **Waiting, not sleeping.** Results are polled (every 0.3 s, well under the gateway's limit) until they are there.
  The saga needs about 1–2 s.
- **Stock through the product cache.** A read that races a stock change can keep an old copy for up to the cache's
  60 s TTL (the known cache-aside limit, step 2.10). So stock checks allow 65 s; normally they pass in under 1 s.
- **Ready means Kafka-ready.** Healthy in Compose means ready for HTTP. A service counts as started only when its
  Kafka listeners also have their partitions; otherwise the first event could wait.
- **Every start is a new container** (`--force-recreate`), so a service's log holds only the current start.

## From the real runs
**On Docker Compose** (2026-11-05, MacBook Air M2, Docker with 3.8 GB):
- 3 runs in a row: **6 of 6 passed** each time, in 90–110 s. Starting the shop takes 26–41 s of that; the tests that
  recreate a container (resilience 26 s, payment failure 17 s) most of the rest.
- In Docker a stopped service is *gone from the network*, not *refusing*: each attempt waits for the gateway's 1 s
  connect timeout, so the first 503 comes after about 4.5 s (4 attempts) instead of under 1 s with jars. Once
  inventory was back, the circuit closed 3 s later.
- Testing the tests: each change below made its test fail.
  - a provider that refuses everything (`SETTINGS`) → the happy path fails (`CANCELLED`)
  - rate limiting switched off in the gateway (compose file) → the rate-limit test fails (15 × 200)
  - the circuit open for 60 s → the resilience test fails (not closed within 40 s)
  - port 8082 taken / Docker not running / no `.env` → exit code 2 with the reason

**Earlier, with the four jars** (step 2.12, 2026-11-04): 6 of 6 in 56–58 s three times; the same breakages caught,
plus payment-service's "already has a payment" check removed → the duplicate test fails. Interesting there: the second
safety net, the event's duplicate mark (`processed_events`), still stopped a second *stored* payment, but only after
the provider had been **charged a second time**. Counting stored payments alone wouldn't have noticed; the log check did.

## Develop
```bash
uv run ruff check . && uv run ruff format .
```
`stack.py` starts and stops the services with `docker compose` (the file `deploy/docker/docker-compose.yml`, profile
`apps`); `shop.py` is the client side: HTTP calls, waits, metrics, Kafka. Next, CI runs the same suite (Phase 3).
