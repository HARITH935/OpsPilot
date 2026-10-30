# OpsPilot — Progress

> Live checklist. Update after every step.
> Legend: `[x]` done · `[ ]` todo · `[~]` in progress

**Current phase:** 0 — Foundation

---

## Phase 0 — Foundation
- [ ] Install JDK 21, Maven, Python 3.11 + uv, Node 20, Docker Desktop, kind, kubectl, Helm, Git
- [x] Create repo layout with placeholder READMEs
- [x] `.gitignore`, `.editorconfig`, root README skeleton, `docs/PROGRESS.md`
- [x] `git init`, first commit, push to GitHub — public repo HARITH935/OpsPilot; `CLAUDE.md` kept local (gitignored)
- [x] Branch protection on `main` (PR required, applies to owner, no force-push/delete; CI check added in Phase 3)
- [x] PR template
- [x] Conventional Commits adopted (enforced via PR template checklist)
- **Acceptance:** [ ] all tool versions print · [x] repo pushed · [x] `PROGRESS.md` lists all phases

## Phase 1 — Core Microservices
- [ ] 1. `order-service` skeleton: health endpoint, layered packages, Actuator
- [ ] 2. PostgreSQL via Docker; Flyway `V1__init.sql`; JPA entities + repositories
- [ ] 3. Order CRUD with DTOs, validation, `ProblemDetail` errors
- [ ] 4. `inventory-service`: products + reservations; seed data migration
- [ ] 5. `payment-service`: simulated provider with failure/latency env vars
- [ ] 6. `common-events` Maven module for the event envelope
- [ ] 7. Tests: unit, `@WebMvcTest`, Testcontainers repository tests
- **Acceptance:** [ ] each service starts · [ ] CRUD works via curl · [ ] `./mvnw verify` green for all three

## Phase 2 — Resilient Distributed Architecture
- [ ] 1. Kafka (KRaft) in Docker; create the domain + OpsPilot topics
- [ ] 2. Transactional outbox + publisher in each service; idempotent consumers
- [ ] 3. Order saga (choreography) incl. compensations and saga-timeout job
- [ ] 4. API gateway: routing, JWT auth (RS256, roles, demo users), JWKS endpoint; Redis in Docker
- [ ] 5. Sliding-window rate limiter (Redis + Lua): per-user / per-IP, 429 + `Retry-After` + `X-RateLimit-*`, Prometheus counter, fail-open
- [ ] 6. Redis cache-aside for product catalog
- [ ] 7. Resilience4j: timeouts, retries, circuit breakers, bulkhead
- [ ] 8. Integration tests for happy path, out-of-stock, payment failure, rate limiter
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
