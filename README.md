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
Java 21 · Spring Boot 3 · Kafka · PostgreSQL · Redis · Kubernetes · Helm · Argo CD · OpenTelemetry ·
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
_TODO (Phase 3): `docker compose up` instructions._

## Progress
See [docs/PROGRESS.md](docs/PROGRESS.md).
