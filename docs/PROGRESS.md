# OpsPilot — Progress

> Live checklist. Update after every step.
> Legend: `[x]` done · `[ ]` todo · `[~]` in progress

**Current phase:** 2 — Resilient Distributed Architecture (Phase 0 complete 2026-10-30 · Phase 1 complete 2026-11-01)

---

## Phase 0 — Foundation
- [x] Install toolchain — JDK 21.0.13, Maven 3.10.0, uv 0.13.6 + Python 3.11.18, Node 22.17 (LTS, ≥ 20), Docker 29.1 + Compose 2.40, kind 0.33, kubectl 1.34, Helm 4.3 ([ADR 0002](adr/0002-helm-4.md)), Git 2.52, gh 2.96
- [x] Create repo layout with placeholder READMEs
- [x] `.gitignore`, `.editorconfig`, root README skeleton, `docs/PROGRESS.md`
- [x] `git init`, first commit, push to GitHub — public repo HARITH935/OpsPilot; `CLAUDE.md` kept local (gitignored)
- [x] Branch protection on `main` (PR required, applies to owner, no force-push/delete; CI check added in Phase 3)
- [x] PR template
- [x] Conventional Commits adopted (enforced via PR template checklist)
- **Acceptance:** [x] all tool versions print · [x] repo pushed · [x] `PROGRESS.md` lists all phases

## Phase 1 — Core Microservices
> Spring Boot 4.1, one Maven parent (`services/pom.xml`), one Postgres with a database per service — [ADR 0003](adr/0003-phase1-foundations.md).
> Every step ships with its own tests (unit, `@WebMvcTest`, Testcontainers) and is one PR.

- [x] 1.1 Maven parent + wrapper; `order-service` skeleton: Actuator health (liveness/readiness), layered packages
- [x] 1.2 PostgreSQL in Docker Compose: one container, databases `orders` / `inventory` / `payments` with own users; `.env.example`
- [x] 1.3 Order schema: Flyway `V1__init.sql` (orders, order_items, outbox, processed_events + indexes); JPA entities (`@Version`) + repositories; Testcontainers repository test
- [x] 1.4a Order API part 1: `POST /api/orders` (202 + `Location`), `GET /api/orders/{id}`; DTO records, validation, RFC 9457 `ProblemDetail` errors; unit + `@WebMvcTest` + end-to-end test. Orders are priced by inventory in the saga — [ADR 0004](adr/0004-orders-priced-by-inventory.md)
- [x] 1.4b Order API part 2: `GET /api/orders?status=&page=&size=` (newest first); `Idempotency-Key` (replay → same order, different body → 422, race-safe via unique constraint; V2 migration)
- [x] 1.5 `inventory-service` products: skeleton, schema, seed-data migration, `GET /api/products`, `GET /api/products/{sku}`, `POST /api/products`
- [x] 1.6 `inventory-service` reservations: reserve / commit / release with optimistic locking + retry; concurrency test (stock never negative)
- [x] 1.7 `payment-service`: schema, simulated provider (`PAYMENT_FAILURE_RATE`, `PAYMENT_LATENCY_MS`), read API; failure-rate test
- [x] 1.8 `common-events` module: event envelope record + event payload records; JSON round-trip tests
- [x] 1.9 Phase 1 wrap-up: `./mvnw verify` green, curl demo of all three services, README "run locally"
- **Acceptance:** [x] each service starts · [x] CRUD works via curl · [x] `./mvnw verify` green for all three

## Phase 2 — Resilient Distributed Architecture
> Spring Cloud Gateway MVC, a shared `common-messaging` module, a polling outbox, open reads + admin-only writes — [ADR 0005](adr/0005-phase2-foundations.md).
> Every step ships with its own tests and is one PR.

- [ ] 2.1 Kafka (KRaft, one broker) in Docker Compose; topics created on startup: `orders` / `inventory` / `payments` `.events` + `.dlq`, `opspilot.incidents`, `opspilot.timeline` (3 partitions each)
- [ ] 2.2 `common-messaging` module: transactional outbox writer + polling publisher (key = order id, at-least-once); order-service publishes `OrderCreated`; Testcontainers Kafka
- [ ] 2.3 Idempotent consumers (`processed_events`) + dead-letter topics after 3 retries; inventory reserves stock on `OrderCreated` → `StockReserved` / `StockRejected`
- [ ] 2.4 Payment charges on `StockReserved` → `PaymentCompleted` / `PaymentFailed`; order copies the prices from `StockReserved` and cancels on `StockRejected`
- [ ] 2.5 Order confirms / cancels on the payment result (`OrderConfirmed` / `OrderCancelled`); inventory commits / releases (`StockReleased`) — compensation complete
- [ ] 2.6 Saga timeout job: orders still `PENDING` after 30 s are cancelled; a payment that arrives after that is handled
- [ ] 2.7 `api-gateway` (:8080, Spring Cloud Gateway MVC): routes to the three services, timeout per route
- [ ] 2.8 JWT (RS256): `POST /auth/token` (demo users viewer / engineer / admin, `DEMO_USERS_ENABLED`), `/.well-known/jwks.json`, roles (`POST /api/products` = ADMIN)
- [ ] 2.9 Redis in Docker + sliding-window rate limiter (one Lua script): per user / per IP, 429 + `Retry-After` + `X-RateLimit-*`, `gateway_ratelimit_*` metrics, fail-open; Locust test
- [ ] 2.10 Redis cache-aside for `GET /api/products/{sku}` (TTL 60 s, evicted when stock changes, fail-open, hit/miss metrics)
- [ ] 2.11 Resilience4j: timeouts, retries with backoff + jitter, circuit breakers (gateway routes, payment provider), bulkhead
- [ ] 2.12 Phase 2 wrap-up: cross-service tests (happy path, out of stock, payment failure, rate limiter), demo, README
- **Acceptance:** [ ] payment failure → order CANCELLED + stock restored · [ ] duplicate events processed once · [ ] Locust test shows 429s with `Retry-After` past the limit · [ ] circuit opens under induced failures

## Phase 3 — Containerization & CI
- [ ] 1. Multi-stage, non-root Dockerfiles (layered JARs); multi-arch builds
- [ ] 2. `docker-compose.yml` for full local stack with health checks
- [ ] 3. GitHub Actions: path-filtered CI, Testcontainers, Trivy, push to GHCR with SHA tags
- **Acceptance:** [ ] `docker compose up` all healthy · [ ] CI green on PR · [ ] images in GHCR

## Phase 4 — Kubernetes Platform
- [ ] 1. kind cluster (3 nodes); namespaces
- [ ] 2. Operators: CloudNativePG, Strimzi, Sealed Secrets; Redis Helm chart (StatefulSet), password from a Kubernetes Secret
- [ ] 3. Helm chart per service (Deployment, Service, ConfigMap, probes, resources, HPA, PDB, NetworkPolicy); umbrella chart
- [ ] 4. Ingress-NGINX routing to gateway and dashboard placeholder
- [ ] 5. Load test to watch HPA scale
- **Acceptance:** [ ] `helm install` brings up full system · [ ] orders work through Ingress · [ ] HPA scales under load · [ ] killed pods recover

## Phase 5 — Go Live
- [ ] 1. Provision Oracle Cloud ARM VM; harden (SSH keys, firewall); install k3s
- [ ] 2. Domain + DNS; Ingress + cert-manager (Let's Encrypt) → HTTPS
- [ ] 3. Argo CD app-of-apps; CI bumps image tags in Git → auto-sync
- [ ] 4. Sealed Secrets for all credentials
- [ ] 5. Locust traffic generator as in-cluster Deployment (daily pattern)
- [ ] 6. Uptime check + smoke tests after each deploy
- **Acceptance:** [ ] public HTTPS URL · [ ] merged PR reaches prod with no manual step · [ ] traffic runs 24/7

## Phase 6 — Full Observability
- [ ] 1. OTel Java agent + Collector; Prometheus (kube-prometheus-stack), Loki, Jaeger, Grafana
- [ ] 2. Custom business metrics: saga completion, outbox backlog, consumer lag
- [ ] 3. Grafana dashboards as code (incl. rate-limiting panel); Alertmanager SLO rules
- **Acceptance:** [ ] one order traceable end-to-end in Jaeger · [ ] logs searchable by `trace_id` · [ ] SLO dashboard live · [ ] rate-limited requests visible in Grafana

## Phase 7 — Chaos Engineering
- [ ] 1. Install Chaos Mesh; toxiproxy for dependency faults
- [ ] 2. Implement C01–C12 YAML + `chaos/run.py` (inject, revert, timeout)
- [ ] 3. Runbook per scenario in `docs/runbooks/`
- **Acceptance:** [ ] each scenario reproducible + visible in Grafana · [ ] auto-revert works

## Phase 8 — Anomaly Detection (Deep Learning)
- [ ] 1. Collect ≥ 24 h normal telemetry → dataset pipeline
- [ ] 2. Train LSTM autoencoder; MLflow tracking + registry
- [ ] 3. Threshold calibration + per-feature attribution
- [ ] 4. Serve as FastAPI deployment publishing `AnomalyDetected` to Kafka
- [ ] 5. Compare against static Alertmanager rules on C01–C12
- **Acceptance:** [ ] detects ≥ 10/12 scenarios · [ ] FPR < 5% over 24 h normal traffic

## Phase 9 — Multi-Agent Investigation
- [ ] 1. FastAPI agent service; Kafka consumer for incidents; Postgres checkpointer
- [ ] 2. MCP servers: prometheus, loki, jaeger, github, kubernetes (read-only)
- [ ] 3. LangGraph graph: triage, dependency analysis (BFS/topological), parallel specialists, hypothesis, verifier loop
- [ ] 4. Langfuse tracing; guardrails (steps, tokens, timeouts)
- [ ] 5. Unit tests for every tool; replay tests with recorded incidents
- **Acceptance:** [ ] correct root cause with cited evidence on ≥ 8/12 scenarios

## Phase 10 — Autonomous Remediation
- [ ] 1. Remediation planner with risk scoring
- [ ] 2. Human approval via LangGraph interrupt + API endpoints
- [ ] 3. Executor: K8s rollback/restart/scale with scoped RBAC
- [ ] 4. Code/config fix: sandbox container → patch → tests → PR
- [ ] 5. Recovery verification + postmortem generation
- **Acceptance:** [ ] C01, C05, C07 return to healthy after approval · [ ] C02/C03 passing fix PR opened

## Phase 11 — Incident Memory & RAG
- [ ] 1. pgvector schema; store each resolved incident with embedding
- [ ] 2. RAG over runbooks; `similar_incidents` tool in the graph
- [ ] 3. Measure diagnosis time with vs without memory
- **Acceptance:** [ ] repeat incidents diagnosed measurably faster · [ ] runbook citations appear in evidence

## Phase 12 — Command Center Dashboard
*Backend first:*
- [ ] 1. GitHub OAuth login in the gateway: JWT cookie, admin allowlist (`ADMIN_GITHUB_LOGINS`), client secret as Sealed Secret
- [ ] 2. Incident history GraphQL API (Strawberry on FastAPI): cursor pagination, DataLoader, depth/alias limits
- [ ] 3. Control-plane Redis cache: TTL per query type, invalidation on incident updates, hit/miss metrics + Grafana hit-ratio panel

*Then the UI:*
- [ ] 4. React + Vite + Tailwind scaffold; "Login with GitHub"; role-based UI
- [ ] 5. Overview: live service map (React Flow) + SLO cards
- [ ] 6. Incident detail: streamed reasoning, evidence, hypotheses, blast-radius graph, Approve/Reject
- [ ] 7. Chaos Lab with login-required, rate-limited "Break it yourself"
- [ ] 8. Evals page; Architecture page; deployed via Helm + Argo CD
- **Acceptance:** [ ] visitor triggers scenario and watches detection → diagnosis live · [ ] admin approves fix from UI · [ ] non-admin GitHub user refused on approve (403) · [ ] repeated GraphQL queries measurably faster with cache · [ ] incident update visible in GraphQL within 1 s

## Phase 13 — Evaluation & Benchmarking
- [ ] 1. `evals/run_all.py`: every scenario × 5 runs on the live cluster
- [ ] 2. Metrics (RCA accuracy, time to detect/diagnose, MTTR, anomaly precision/recall, cost); ML vs static-rule comparison; memory ablation
- [ ] 3. Auto-generate `evals/RESULTS.md` + dashboard page
- **Acceptance:** [ ] results table published · [ ] headline numbers meet or are honestly reported against targets

## Phase 14 — Showcase
- [ ] README: problem, live link, architecture diagram, results, stack, local setup, screenshots/GIFs
- [ ] 2-minute demo video: break → detect → diagnose → approve → recover
- [ ] Resume bullets + LinkedIn post; optional blog write-up
- **Acceptance:** [ ] understandable in 60 s · [ ] runnable locally in 15 min

---

## Log
| Date | Step | Notes |
|---|---|---|
| 2026-10-29 | Phase 0: repo layout, .gitignore, .editorconfig, README, PROGRESS | Tool check: JDK 17 (need 21), Python 3.13 (need 3.11 via uv); Maven, uv, kind, Helm missing |
| 2026-10-29 | Phase 0: pushed to GitHub (public), PR template, branch protection | Remaining: install JDK 21, Maven, uv, kind, Helm |
| 2026-10-30 | Plan: added GitHub OAuth, sliding-window rate limiting, Redis cache, GraphQL | See [ADR 0001](adr/0001-backend-extras.md) |
| 2026-10-30 | Phase 0 complete: freed disk (14 → 58 GB), installed toolchain, Java 21 default, Docker limits checked (3.8 GB / 8 CPU, kept) | Helm 4 instead of 3 — [ADR 0002](adr/0002-helm-4.md) |
| 2026-10-30 | Phase 1 planned: 9 sub-phases, tests in every step | Spring Boot 4.1, Maven parent, one Postgres — [ADR 0003](adr/0003-phase1-foundations.md) |
| 2026-10-30 | 1.1 order-service skeleton: Spring Boot 4.1.2, health/liveness/readiness/prometheus, 3 tests green | Starts in 1.0 s, 201 MB; port 8081 used by an Expo server, verified on 18081 |
| 2026-10-30 | 1.2 Postgres 16.15 in Docker Compose: orders / inventory / payments DBs, one user each, CONNECT revoked from others | Verified: cross-DB access denied, wrong password denied, survives restart, 18 MB RAM |
| 2026-10-30 | 1.3 order schema: Flyway V1 (4 tables, 3 indexes), Order/OrderItem entities with @Version, OrderRepository | 12 tests green (Testcontainers Postgres 16.15); Flyway applied V1 on local DB, skipped on restart |
| 2026-10-30 | 1.4a Order API: create (202 + Location) and get; validation; ProblemDetail errors | 23 tests green; curl: 202 / 200 / 400 with field errors / 404 against local Postgres |
| 2026-10-31 | 1.4b list orders (paged, newest first) + Idempotency-Key (replay, 422, race-safe), Flyway V2 | 47 tests green; race test: 8 concurrent requests → 1 insert, 7 caught by the unique constraint |
| 2026-10-31 | 1.5 inventory-service products: Flyway V1 (products with CHECK rules, outbox, processed_events) + V2 seed (10 products); list (paged, by SKU), get, create (201; duplicate SKU → 409, race-safe) | 33 new tests (80 total) green; race test: 8 concurrent creates → 1 insert, 7 caught by the primary key; curl on local Postgres: 200 / 201 / 400 / 404 / 409 |
| 2026-10-31 | 1.6 inventory reservations: Flyway V3; reserve (all or nothing, priced) / commit / release; optimistic locking + Spring 7 `@Retryable` (5 retries, backoff + jitter); UPDATEs sorted by SKU (no deadlocks) | 25 new tests (58 inventory, 105 total) green; concurrency test, 20 orders vs. the last 5 units → 5 held, 15 rejected, stock never negative; over 6 runs 21–31 conflicts retried per run, 0 exhausted, 0 deadlocks |
| 2026-11-01 | 1.7 payment-service: Flyway V1 (payments: one per order, a reason exactly when failed; outbox, processed_events); simulated provider (`PAYMENT_FAILURE_RATE` / `PAYMENT_LATENCY_MS`, validated at startup); charge (never twice, provider called outside transactions); read API (list newest first, get) | 41 new tests (146 total) green; failure-rate test: 10,000 charges at 30% → 2,924 failures; real run with rate 0.5 / 200 ms; `PAYMENT_FAILURE_RATE=2` → refused to start |
| 2026-11-01 | 1.8 common-events: plain library with `EventEnvelope` (B2.1 fields; can't contradict its payload), 8 saga event records (sealed `EventPayload`), `EventType` catalog (JSON name ↔ record ↔ topic), `EventJson` (fixed format, tolerant reader) | 21 tests (167 total) green; JSON round trip for all 8 events; exact wire shape checked against B2.1; jar 20 KB, 17 classes, no Spring |
| 2026-11-01 | 1.9 Phase 1 wrap-up — **Phase 1 complete**: `./mvnw verify` on main; all three services running together on local Postgres; curl demo; root README "Run locally" | 167 tests green (36 s); all three ready in ~4 s, 705 MB together; demo: catalogue 200, new product 201 / duplicate 409, order 202 + retry replayed (same order), payments 200 (empty until the Phase 2 saga) |
| 2026-11-01 | Phase 2 planned: 12 sub-steps, tests in every step | Gateway MVC, `common-messaging`, polling outbox, open reads + admin-only writes — [ADR 0005](adr/0005-phase2-foundations.md) |
