# OpsPilot

> A live, self-healing platform for cloud-native microservices — it detects failures with deep learning,
> investigates them with a multi-agent AI system, and proposes human-approved fixes.

**Live demo:** _coming in Phase 5_ · **Results:** _coming in Phase 13_

[![ci-java](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-java.yml/badge.svg?branch=main)](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-java.yml)
[![ci-python](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-python.yml/badge.svg?branch=main)](https://github.com/HARITH935/OpsPilot/actions/workflows/ci-python.yml)
[![k8s](https://github.com/HARITH935/OpsPilot/actions/workflows/k8s.yml/badge.svg?branch=main)](https://github.com/HARITH935/OpsPilot/actions/workflows/k8s.yml)

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
Today (Phases 1–3 done): the three services and the API gateway, with PostgreSQL, Kafka and
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

**Kubernetes (Phase 4, in progress):**
- `deploy/k3s/kind-up.sh` creates a local one-node kind cluster in about 3.5 minutes. It comes with OpsPilot's
  namespaces (strict Pod Security), metrics-server and the operators for PostgreSQL (CloudNativePG) and Kafka (Strimzi).
- `deploy/k3s/secrets-from-env.sh` and two Helm charts add the data layer, each checked by `helm test`:
  - [`postgres`](deploy/helm/README.md#postgres): PostgreSQL 16, one database and login per service
  - [`redis`](deploy/helm/README.md#redis): our own Redis chart (a StatefulSet)
  - [`kafka`](deploy/helm/README.md#kafka): Kafka 4.2.2 and its 8 topics, run by the Strimzi operator
- `deploy/k3s/shop-up.sh` brings up the whole shop: Secrets, the data layer, then the four services from one
  [generic chart](deploy/helm/README.md#opspilot-the-four-services). `helm test opspilot -n opspilot-apps` places an
  order through the gateway and waits until it is CONFIRMED.
- The front door is **Traefik with the Gateway API** ([ADR 0013](docs/adr/0013-traefik-and-gateway-api.md); Ingress-NGINX
  was retired in March 2026): `curl http://localhost/api/products` reaches the shop; only `/api`, `/auth` and
  `/.well-known` are routed.
- Needs Docker Desktop with 5 GB; stop the Compose stack first.
- Details: [deploy/k3s](deploy/k3s/README.md), [ADR 0009](docs/adr/0009-lean-local-kubernetes.md),
  [ADR 0010](docs/adr/0010-postgres-on-kubernetes.md), [ADR 0011](docs/adr/0011-kafka-on-kubernetes.md),
  [ADR 0012](docs/adr/0012-one-generic-service-chart.md), [ADR 0013](docs/adr/0013-traefik-and-gateway-api.md).

## What Phase 2 can do
| | Shown by | Result (real runs, 2026-11-04) |
|---|---|---|
| A payment fails → the order is cancelled and its stock restored | `e2e/` `test_saga.py` | `CANCELLED PAYMENT_FAILED`, stock exactly as before |
| The same Kafka event twice → handled once | `e2e/` `test_duplicates.py` | a real `StockReserved` sent again: one payment, nothing changed |
| Too many requests → 429 with `Retry-After`, counted | [`loadgen/`](loadgen/README.md) (40 shoppers, 30 s) | 1092 requests, 165 × 429, all correct; `gateway_ratelimit_rejected_total` +165 |
| A service down → its circuit opens, then closes again | `e2e/` `test_resilience.py` | 503 + `Retry-After` in under 0.1 s, closed again once the service is back |
| A struggling payment provider (50% errors) | [payment-service](services/payment-service/README.md#protection-around-the-provider) | 37 of 40 orders confirmed thanks to retries ([ADR 0007](docs/adr/0007-provider-breaker-judges-payments.md)) |

## What Phase 3 can do
| | Shown by | Result (real runs, 2026-11-04 … 09) |
|---|---|---|
| The whole shop with one command, no JDK | [deploy/docker](deploy/docker/README.md#the-shop-profile-apps) (`--profile apps up -d --build --wait`) | from nothing to everything healthy in 47 s; 1.6 GB; order `CONFIRMED` |
| Small, safe images for two chips | [services/Dockerfile](services/Dockerfile), [ADR 0008](docs/adr/0008-phase3-foundations.md) | 176 MB, our code a 100 kB layer; user 10001, read-only, no Linux capabilities; amd64 + arm64 |
| Every PR checked before it can be merged | [CI](#checks-on-every-pull-request-ci): 5 required checks | a deliberately broken test → ❌ and merge BLOCKED; docs-only PR → checks skipped, mergeable |
| No image with a known HIGH / CRITICAL hole is published | `images` (Trivy) | first scan found 3 (`apt`, `lz4-java`, `snappy-java`), fixed; now 0 findings in all 442 checked parts |
| The end-to-end tests on a clean machine | `e2e` in CI | 6 of 6 on GitHub's runner in 3.4 min (images built 73 s, tests 127 s) |
| Images ready for Kubernetes | GHCR (public) | `ghcr.io/harith935/opspilot-<service>:sha-<commit>`; pulled on an arm64 Mac without login, started, `UP` |

## Checks on every pull request (CI)
GitHub Actions runs these on every PR to `main` and every push to `main`; a PR can only be merged when they pass:
| Check | Runs | When |
|---|---|---|
| `java-verify` ([ci-java.yml](.github/workflows/ci-java.yml)) | `./mvnw verify` in `services/`: all modules, all tests (Testcontainers on the runner's Docker) | `services/` changed |
| `python-loadgen` ([ci-python.yml](.github/workflows/ci-python.yml)) | `ruff check`, `ruff format --check`, `pytest` in `loadgen/` | `loadgen/` changed |
| `python-e2e` ([ci-python.yml](.github/workflows/ci-python.yml)) | `ruff check`, `ruff format --check` in `e2e/` | `e2e/` changed |
| `images` ([images.yml](.github/workflows/images.yml)) | the four images for amd64 + arm64, scanned by Trivy (HIGH / CRITICAL fail) | `services/` changed |
| `e2e` ([e2e.yml](.github/workflows/e2e.yml)) | the whole shop in Docker Compose on the runner, then the 6 [end-to-end tests](e2e/README.md) | `services/`, `e2e/` or `deploy/docker/` changed |
| `k8s` ([k8s.yml](.github/workflows/k8s.yml)) | a **three-node** kind cluster on the runner, the whole shop installed with [`kind-up.sh` + `shop-up.sh`](deploy/k3s/README.md), every chart's `helm test`, pods only on the workers, Pod Security "restricted" clean | `deploy/helm/`, `deploy/k3s/` or `deploy/k8s/` changed |

On `main`, the job `publish` builds and scans the same way and then pushes the images to GHCR (public):
`ghcr.io/harith935/opspilot-<service>:sha-<short commit>` ([details](services/README.md#container-images)).

A check whose folder didn't change is *skipped*, which counts as passed, so a docs-only PR isn't held up. On pushes to
`main`, `ci-java` and `ci-python` always run everything; `images` / `publish`, `e2e` and `k8s` run when their folders changed.
The workflows may only read the repo (only `publish` may write packages), and every action is pinned to a full commit.

## Progress
See [docs/PROGRESS.md](docs/PROGRESS.md).
