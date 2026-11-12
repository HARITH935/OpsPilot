# Architecture Decision Records

One file per decision that deviates from or extends the original architecture design, named `NNNN-title.md`.
Each records the context, the decision, the alternatives considered and the consequences.

| ADR | Decision | Date |
|---|---|---|
| [0001](0001-backend-extras.md) | Backend extras: GitHub OAuth, sliding-window rate limiting, control-plane cache, GraphQL | 2026-10-30 |
| [0002](0002-helm-4.md) | Use Helm 4 instead of Helm 3 | 2026-10-30 |
| [0003](0003-phase1-foundations.md) | Spring Boot 4.1, one Maven parent, one PostgreSQL with a database per service | 2026-10-30 |
| [0004](0004-orders-priced-by-inventory.md) | Orders are priced by inventory during the saga | 2026-10-30 |
| [0005](0005-phase2-foundations.md) | Phase 2: Spring Cloud Gateway MVC, a shared messaging module, a polling outbox, open reads | 2026-11-01 |
| [0006](0006-refund-late-payments.md) | Refund a payment that completed after its order was cancelled | 2026-11-03 |
| [0007](0007-provider-breaker-judges-payments.md) | The payment provider's circuit breaker judges payments, not attempts | 2026-11-04 |
| [0008](0008-phase3-foundations.md) | Phase 3: one Dockerfile for all services, the jar built inside Docker, Temurin 21 base images | 2026-11-04 |
| [0009](0009-lean-local-kubernetes.md) | Phase 4: a one-node kind cluster locally, the full three-node cluster in CI | 2026-11-09 |
| [0010](0010-postgres-on-kubernetes.md) | PostgreSQL on Kubernetes: CloudNativePG in opspilot-data, local Secrets from .env, Sealed Secrets in Phase 5 | 2026-11-09 |
| [0011](0011-kafka-on-kubernetes.md) | Kafka on Kubernetes: Strimzi in opspilot-data, one KRaft node, topics as KafkaTopic objects | 2026-11-10 |
| [0012](0012-one-generic-service-chart.md) | The four services from one generic Helm chart, bundled by the umbrella chart opspilot | 2026-11-11 |
| [0013](0013-traefik-and-gateway-api.md) | The front door: Traefik and the Gateway API instead of Ingress-NGINX | 2026-11-12 |
| [0014](0014-network-policies.md) | NetworkPolicies: default-deny per namespace, allows live next to what they protect | 2026-11-12 |
