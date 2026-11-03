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
