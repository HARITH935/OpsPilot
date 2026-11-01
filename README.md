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
| `services/` | Java microservices + shared event module |
| `ml/anomaly-detector/` | LSTM autoencoder training + serving |
| `agent/` | LangGraph agents + MCP tool servers |
| `dashboard/` | React Command Center |
| `chaos/` | Chaos scenarios C01–C12 |
| `loadgen/` | Locust traffic |
| `evals/` | Benchmark harness + results |
| `deploy/` | Docker Compose, Helm, Argo CD, k3s |
| `docs/` | Progress, ADRs, runbooks |

## Run locally
Today (Phase 2 in progress): PostgreSQL and Kafka in Docker, the three services on your machine. Each service still
works on its own: Kafka is running, and Phase 2 connects the services through it step by step (the order saga).
Phase 3 turns all of this into a single `docker compose up`.

**You need:** JDK 21 and Docker Desktop (running).

```bash
# 1. Postgres and Kafka: copy the template, fill in your own passwords, start both (Kafka's topics are created for you)
cp -n deploy/docker/.env.example deploy/docker/.env   # -n: never overwrites a .env you already have
docker compose -f deploy/docker/docker-compose.yml up -d
deploy/docker/kafka/smoke-test.sh                     # optional: checks Kafka and its topics

# 2. Build and test everything (the tests start throwaway Postgres containers, so Docker must be running)
cd services && ./mvnw verify && cd ..

# 3. Start each service in its own terminal (run from the repo root)
DB_PASSWORD=$(grep '^ORDERS_DB_PASSWORD=' deploy/docker/.env | cut -d= -f2) \
  java -Xmx256m -jar services/order-service/target/order-service-0.0.1-SNAPSHOT.jar
DB_PASSWORD=$(grep '^INVENTORY_DB_PASSWORD=' deploy/docker/.env | cut -d= -f2) \
  java -Xmx256m -jar services/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar
DB_PASSWORD=$(grep '^PAYMENTS_DB_PASSWORD=' deploy/docker/.env | cut -d= -f2) \
  java -Xmx256m -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
```
`-Xmx256m` keeps each service small (about 700 MB for all three). If a port is taken, add `SERVER_PORT=<free port>`
in front, e.g. `SERVER_PORT=18081` for order-service.

| Service | Port | Try it | Full API |
|---|---|---|---|
| order-service | 8081 | `curl -i -X POST localhost:8081/api/orders -H 'Content-Type: application/json' -d '{"customerId":"c-1","items":[{"sku":"SKU-1","qty":2}]}'` | [README](services/order-service/README.md) |
| inventory-service | 8083 | `curl 'localhost:8083/api/products?size=3'` | [README](services/inventory-service/README.md) |
| payment-service | 8082 | `curl localhost:8082/api/payments` | [README](services/payment-service/README.md) |

Every service also has `/actuator/health` (plus `/liveness` and `/readiness`) and `/actuator/prometheus`.
Until the saga is connected (Phase 2, steps 2.2–2.5), a new order stays `PENDING` without prices: nothing reserves its
stock or charges it yet.

## Progress
See [docs/PROGRESS.md](docs/PROGRESS.md).
