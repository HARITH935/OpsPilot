# ADR 0015 — Phase 6 (observability) is built before Phase 5 (go live)

- **Status:** Accepted
- **Date:** 2026-11-13

## Context
Part C says the phases are worked "strictly in order", and Phase 5 (Go Live) comes before Phase 6 (Full
Observability). Phase 4 finished on 2026-11-13, so Phase 5 was next.

Phase 5 needs a cloud machine. The plan is an Oracle Cloud **Always Free** ARM VM (B8), and creating that account
needs a **credit card** for identity verification. The developer has none: an Indian debit card was declined, and
Oracle's own FAQ says it accepts credit cards and "debit cards that function like credit cards", but **not** debit
cards with a PIN, nor virtual, single-use or prepaid cards — which rules out Indian retail debit cards generally, not
just one bank's. Asking a family member for a card is possible but has no date attached.

Phase 5 is also the only phase nothing else depends on. Phases 7–9 all read from Phase 6:

- **Phase 7 (chaos)** requires each scenario to be "clearly visible in Grafana".
- **Phase 8 (anomaly detection)** trains an LSTM autoencoder on ≥ 24 h of **Prometheus** metrics.
- **Phase 9 (agents)** investigates incidents with MCP servers for **Prometheus, Loki and Jaeger** (B5.3), and builds
  its service dependency graph from **Jaeger spans** (B5.2).

So waiting for a card would stop all work, while doing Phase 6 first unblocks four phases. The heavy checks have run
on a three-node kind cluster in CI since step 4.5 (ADR 0009), and that cluster can run the observability stack too —
no public machine needed.

## Decision
1. **Phase 6 is built now; Phase 5 is deferred** until a usable payment method exists. Both keep their numbers; only
   the order changes. `docs/PROGRESS.md` is the live record of what is done.
2. **Phase 6 is proven in CI**, on the same three-node kind cluster as Phase 4, in the `k8s` workflow.
3. **Phase 5 is picked up as soon as the account exists**, and before Phase 8 needs its ≥ 24 h of real traffic
   (collecting that is far easier on a machine that runs all day than on a laptop or in CI).
4. **Nothing in Phase 6 may assume a public cluster.** Ingress host names, TLS and Argo CD belong to Phase 5; the
   charts get values files, so Phase 5 only adds a values file, not a rewrite.
5. **An observability profile is added to Docker Compose** as well (step 6.6). The Kubernetes cluster only exists
   inside CI, so without it the developer cannot open a Grafana dashboard or a Jaeger trace at all before Phase 5 —
   bad for a learning project, and there would be no screenshots for the README (Phase 14).

## Alternatives considered
- **Wait for the card and build nothing.** Rejected: no deadline exists for getting one, and four phases are blocked
  behind Phase 6 while nothing is blocked behind Phase 5.
- **Pay for a small VM** (e.g. Hetzner, ~€4/month). Rejected for now: it needs a payment method too, and this is a
  student project that the Always Free tier was chosen for on purpose (B8).
- **Azure for Students** ($100 of credit, verified with a college email instead of a card). Not rejected — it stays
  the backup if no credit card appears. It was not chosen now because it would change the cloud provider for the
  whole project, and its credit expires after 12 months, while Always Free does not.
- **Do only the parts of Phase 5 that need no cloud** (the in-cluster Locust Deployment, step 5). Rejected as the
  main plan: it is one small task, and afterwards the wait would simply resume. It moves into Phase 6's CI work
  instead, where traffic is needed to make the dashboards show anything.

## Consequences
- The public demo arrives later than planned. Everything that proves OpsPilot works — the detector, the agents, the
  benchmarks — is unaffected, since all of it is measured on the CI cluster anyway (Phase 13).
- Phase 8's "≥ 24 h of normal traffic" is the first point where the missing machine really hurts. If no card exists by
  then, that dataset has to come from somewhere else, and this ADR gets a follow-up.
- Phase 6 is built twice over: once for Kubernetes (Helm, in CI) and once for Compose (local). That is not wasted
  work — Compose is how the developer actually sees the dashboards, and both are needed in the end anyway.
- When Phase 5 happens, its Argo CD app-of-apps must include the observability charts from the start.
