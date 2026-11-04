# OpsPilot

> A live, self-healing platform for cloud-native microservices — it detects failures with deep learning,
> investigates them with a multi-agent AI system, and proposes human-approved fixes.

**Live demo:** _coming in Phase 5_ · **Results:** _coming in Phase 13_

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
| `evals/` | Benchmark harness + results |
| `deploy/` | Docker Compose, Helm, Argo CD, k3s |
| `docs/` | Progress, ADRs, runbooks |

## Run locally
Today (Phase 2 in progress): PostgreSQL, Kafka and Redis in Docker, the three services and the API gateway on your machine. Phase 2 connects
the services through Kafka step by step (the order saga): an order is reserved and priced by inventory, charged by
payment, then confirmed (stock committed), or cancelled (out of stock, or payment failed and the stock released).
Phase 3 turns all of this into a single `docker compose up`.

**You need:** JDK 21 and Docker Desktop (running).

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

## Progress
See [docs/PROGRESS.md](docs/PROGRESS.md).
