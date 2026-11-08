# OpsPilot

> A live, self-healing platform for cloud-native microservices — it detects failures with deep learning,
> investigates them with a multi-agent AI system, and proposes human-approved fixes.

**Live demo:** _coming in Phase 5_ · **Results:** _coming in Phase 13_

[![ci-java](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-java.yml/badge.svg?branch=main)](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-java.yml)
[![ci-python](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-python.yml/badge.svg?branch=main)](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-python.yml)

---

## The problem
_TODO (Phase 14): why incident response is slow and manual, and what OpsPilot automates._

## How it works
_TODO: architecture diagram._

1. **Data plane** — Order, Payment and Inventory services behind an API Gateway, running 24/7 on Kubernetes.
2. **Detect** — an LSTM autoencoder flags anomalies in streaming telemetry.
3. **Diagnose** — a LangGraph multi-agent system gathers evidence and verifies root-cause hypotheses.
4. **Remediate** — a rollback / restart / scale or a tested fix PR, executed only after human approval.
5. **Watch it live** — the Command Center dashboard, including "Break it yourself".

## Results
_TODO (Phase 13): table from `evals/RESULTS.md`._

## Tech stack
Java 21 · Spring Boot 4 · Kafka · PostgreSQL · Redis · Kubernetes · Helm · Argo CD · OpenTelemetry ·
Prometheus · Grafana · PyTorch · LangGraph · FastAPI · React · TypeScript

## Repository layout
| Path | What's inside |
|---|---|
| `services/` | Java microservices + shared event and messaging modules |
| `ml/anomaly-detector/` | LSTM autoencoder training + serving |
| `agent/` | LangGraph agents + MCP tool servers |
| `dashboard/` | React Command Center |
| `chaos/` | Chaos scenarios C01–C12 |
| `loadgen/` | Locust traffic |
| `e2e/` | End-to-end tests: the whole shop as a black box (pytest) |
| `evals/` | Benchmark harness + results |
| `deploy/` | Docker Compose, Helm, Argo CD, k3s |
| `docs/` | Progress, ADRs, runbooks |

## Run locally
Today (Phases 1 and 2 done, Phase 3 in progress): the three services and the API gateway, with PostgreSQL, Kafka and
Redis. The services talk through Kafka (the order saga): an order is reserved and priced by inventory, charged by
payment, then confirmed (stock committed), or cancelled (out of stock, or payment failed and the stock released).

### The quick way: everything in Docker
**You need:** Docker Desktop (running). No JDK.
```bash
cp -n deploy/docker/.env.example deploy/docker/.env   # then replace every change-me with your own passwords
docker compose -f deploy/docker/docker-compose.yml --profile apps up -d --build --wait   # ~1 min the first time
curl 'localhost:8080/api/products?size=3'             # the shop's front door
docker compose -f deploy/docker/docker-compose.yml --profile apps down                   # stop (keeps the data)
```
This builds the four service images and starts them with Postgres, Kafka and Redis, each one only after what it
needs is healthy (about 1.6 GB of memory). Details, ports and settings: [deploy/docker](deploy/docker/README.md#the-shop-profile-apps).

### The developer way: services on your machine
**You need:** JDK 21 and Docker Desktop (running). Docker runs only Postgres, Kafka and Redis (no profile); stop the
shop above first, or each service runs twice.

```bash
# 1. Postgres, Kafka and Redis: copy the template, fill in your own passwords, start them (Kafka's topics are created for you)
cp -n deploy/docker/.env.example deploy/docker/.env   # -n: never overwrites a .env you already have
docker compose -f deploy/docker/docker-compose.yml up -d
deploy/docker/kafka/smoke-test.sh                     # optional: checks Kafka and its topics

# 2. Build and test everything (the tests start throwaway Postgres containers, so Docker must be running)
cd services && ./mvnw verify && cd ..

# 3. Start each service in its own terminal (run from the repo root)
DB_PASSWORD=$(grep '^ORDERS_DB_PASSWORD=' deploy/docker/.env | cut -d= -f2) \
  java -Xmx256m -jar services/order-service/target/order-service-0.0.1-SNAPSHOT.jar
DB_PASSWORD=$(grep '^INVENTORY_DB_PASSWORD=' deploy/docker/.env | cut -d= -f2) \
  REDIS_PASSWORD=$(grep '^REDIS_PASSWORD=' deploy/docker/.env | cut -d= -f2-) \
  java -Xmx256m -jar services/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar   # Redis: its product cache
DB_PASSWORD=$(grep '^PAYMENTS_DB_PASSWORD=' deploy/docker/.env | cut -d= -f2) \
  java -Xmx256m -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar

# 4. The front door on :8080 (tell it where order-service is if you moved it, e.g. ORDER_SERVICE_URL=http://localhost:18081),
#    with the demo logins viewer / engineer / admin (their password is DEMO_USERS_PASSWORD in deploy/docker/.env)
#    and the rate limiter's Redis (REDIS_PASSWORD)
DEMO_USERS_ENABLED=true DEMO_USERS_PASSWORD=$(grep '^DEMO_USERS_PASSWORD=' deploy/docker/.env | cut -d= -f2-) \
  REDIS_PASSWORD=$(grep '^REDIS_PASSWORD=' deploy/docker/.env | cut -d= -f2-) \
  java -Xmx128m -jar services/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
```
`-Xmx256m` keeps each service small (about 700 MB for all three, plus about 150 MB for the gateway). If a port is
taken, add `SERVER_PORT=<free port>` in front, e.g. `SERVER_PORT=18081` for order-service.

**Use port 8080 for everything:** the [API gateway](services/api-gateway/README.md) sends `/api/orders/**`,
`/api/products/**` and `/api/payments/**` to the right service. A stopped service answers 503 there, a too slow one 504.
Reading and ordering are open to everyone. Adding a product needs an ADMIN token: log in with `POST /auth/token`,
then send `Authorization: Bearer <token>` ([how](services/api-gateway/README.md#login-and-roles-jwt)).
Too many requests in a short time get **429** with `Retry-After`: 10 per second per address without a token, 20 with
one ([rate limiting](services/api-gateway/README.md#rate-limiting)). To see it under load, run the Locust test in
[`loadgen/`](loadgen/README.md) (needs [uv](https://docs.astral.sh/uv/)). A product read (`GET /api/products/{sku}`) is
answered from a Redis copy for up to 60 s, deleted as soon as its stock changes ([product cache](services/inventory-service/README.md#product-cache)).
Each route has its own timeout (2 s → 504), a circuit breaker (503 at once for 30 s after half of the last 20 calls
failed) and, for GET, up to 3 retries with random waits ([resilience](services/api-gateway/README.md#resilience-timeout-circuit-breaker-retries-per-route)).
payment-service protects its calls to the payment provider the same way, plus a bulkhead: a failed payment says why
(`PROVIDER_ERROR`, `PROVIDER_TIMEOUT` or `PROVIDER_UNAVAILABLE`, [details](services/payment-service/README.md#protection-around-the-provider)).

| Service | Port | Try it | Full API |
|---|---|---|---|
| **api-gateway** | **8080** | `curl 'localhost:8080/api/products?size=3'` (any path below works here) | [README](services/api-gateway/README.md) |
| order-service | 8081 | `curl -i -X POST localhost:8081/api/orders -H 'Content-Type: application/json' -d '{"customerId":"c-1","items":[{"sku":"SKU-1","qty":2}]}'` | [README](services/order-service/README.md) |
| inventory-service | 8083 | `curl 'localhost:8083/api/products?size=3'` | [README](services/inventory-service/README.md) |
| payment-service | 8082 | `curl localhost:8082/api/payments` | [README](services/payment-service/README.md) |

Every service also has `/actuator/health` (plus `/liveness` and `/readiness`) and `/actuator/prometheus`.
With all three running, a new order is `CONFIRMED` about 1–2 s after the `POST` (check `GET /api/orders/{id}`).
Start payment-service with `PAYMENT_FAILURE_RATE=1.0` in front to see the other ending: `CANCELLED` with reason
`PAYMENT_FAILED`, and the stock back on sale. Leave payment-service off and the order is `CANCELLED` with reason
`TIMEOUT` about 30 s later (the saga timeout), its stock released too. Start payment-service after that: it charges
the order late, and the money is refunded within about a second (`GET /api/payments?status=REFUNDED`).

**Check it all at once:** the [end-to-end tests](e2e/README.md) start the shop in Docker (the same images as the quick
way, built from the current code) and run the saga (confirmed, out of stock, payment failed), a duplicate Kafka event,
the circuit breaker and the rate limiter through the gateway, in about 1.5–2 minutes. Needs only Docker and
[uv](https://docs.astral.sh/uv/); stop your own running services or the shop first:
```bash
cd e2e && uv sync && uv run pytest
```

**Images (Phase 3):** one [Dockerfile](services/Dockerfile) builds an image of any of the four services, with no
JDK or Maven on your machine: `cd services && docker build --build-arg SERVICE=order-service -t opspilot/order-service:dev .`
Non-root, layered (a code change replaces ~100 kB), for amd64 and arm64. Details: [services/README.md](services/README.md#container-images).

## What Phase 2 can do
| | Shown by | Result (real runs, 2026-11-04) |
|---|---|---|
| A payment fails → the order is cancelled and its stock restored | `e2e/` `test_saga.py` | `CANCELLED PAYMENT_FAILED`, stock exactly as before |
| The same Kafka event twice → handled once | `e2e/` `test_duplicates.py` | a real `StockReserved` sent again: one payment, nothing changed |
| Too many requests → 429 with `Retry-After`, counted | [`loadgen/`](loadgen/README.md) (40 shoppers, 30 s) | 1092 requests, 165 × 429, all correct; `gateway_ratelimit_rejected_total` +165 |
| A service down → its circuit opens, then closes again | `e2e/` `test_resilience.py` | 503 + `Retry-After` in under 0.1 s, closed again once the service is back |
| A struggling payment provider (50% errors) | [payment-service](services/payment-service/README.md#protection-around-the-provider) | 37 of 40 orders confirmed thanks to retries ([ADR 0007](docs/adr/0007-provider-breaker-judges-payments.md)) |

## Checks on every pull request (CI)
GitHub Actions runs these on every PR to `main` and every push to `main`; a PR can only be merged when they pass:
| Check | Runs | When |
|---|---|---|
| `java-verify` ([ci-java.yml](.github/workflows/ci-java.yml)) | `./mvnw verify` in `services/`: all modules, all tests (Testcontainers on the runner's Docker) | `services/` changed |
| `python-loadgen` ([ci-python.yml](.github/workflows/ci-python.yml)) | `ruff check`, `ruff format --check`, `pytest` in `loadgen/` | `loadgen/` changed |
| `python-e2e` ([ci-python.yml](.github/workflows/ci-python.yml)) | `ruff check`, `ruff format --check` in `e2e/` | `e2e/` changed |
| `images` ([images.yml](.github/workflows/images.yml)) | the four images for amd64 + arm64, scanned by Trivy (HIGH / CRITICAL fail) | `services/` changed |

On `main`, the job `publish` builds and scans the same way and then pushes the images to GHCR:
`ghcr.io/harith935/opspilot-<service>:sha-<short commit>` ([details](services/README.md#container-images)).

A check whose folder didn't change is *skipped*, which counts as passed, so a docs-only PR isn't held up. Pushes to
`main` always run everything. The workflows may only read the repo, and every action is pinned to a full commit.

## Progress
See [docs/PROGRESS.md](docs/PROGRESS.md).
