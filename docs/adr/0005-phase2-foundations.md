# ADR 0005 — Phase 2: Spring Cloud Gateway MVC, a shared messaging module, a polling outbox, open reads

- **Status:** Accepted
- **Date:** 2026-11-01

## Context
Phase 2 connects the three services through Kafka (a choreographed saga with a transactional outbox and idempotent
consumers) and adds an API gateway (routing, JWT, rate limiting) and Resilience4j. Several of these building blocks
can be built in more than one reasonable way. Constraints: code the developer can learn and explain, an 8 GB development
machine, and the fixed tech stack (CLAUDE.md §5).
Spring Cloud **2025.1.4** supports Spring Boot 4.1.x. On start.spring.io, "Gateway" is now the Spring MVC (servlet)
gateway, and "Reactive Gateway" is the WebFlux one.

## Decision
1. **Spring Cloud Gateway Server Web MVC** for `api-gateway` (port 8080), on Spring Cloud 2025.1.x.
2. **One shared module, `common-messaging`** (a Spring library): the outbox writer, the outbox publisher, the duplicate
   check for consumers (`processed_events`) and the Kafka producer / consumer settings (dead-letter topic after 3 retries).
   Each service imports it explicitly and keeps its own tables (every V1 migration already has the same `outbox` and
   `processed_events` layout) and its own events. `common-events` stays a plain contract library without Spring.
3. **A polling outbox publisher:** a scheduled job reads unpublished outbox rows, oldest first, about every 500 ms, with
   `SELECT … FOR UPDATE SKIP LOCKED`. It sends each envelope to its topic with the order id as key, then marks the row
   published. Delivery is at-least-once; consumers drop duplicates by `eventId`.
4. **Access rules at the gateway:** reading products, orders and payments, and placing orders, are open to everyone
   (rate-limited, with a lower limit for anonymous clients by IP). `POST /api/products` needs the ADMIN role. Tokens come
   from `POST /auth/token` for the demo users `viewer` / `engineer` / `admin`, only when `DEMO_USERS_ENABLED=true`
   (local and evals; false in the cloud, where GitHub login arrives in Phase 12).

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Reactive Spring Cloud Gateway (WebFlux) | Non-blocking, but a different programming model (`Mono` / `Flux`) from the three services; the JWT and rate-limiter code would have to be reactive too. With Java 21 virtual threads the servlet gateway handles this traffic comfortably. |
| A copy of the outbox / consumer code in every service | Three near-identical copies to keep in step; every bug fixed three times. |
| The messaging code inside `common-events` | `common-events` would then need Spring, JPA and Kafka; the event contract should stay a plain library any consumer can use. |
| Change data capture with Debezium (reads the Postgres log) instead of polling | Lower latency and no polling queries, but Kafka Connect + Debezium is another heavy component for an 8 GB machine and the cloud budget, and not in the fixed stack. |
| A login for every request | The public demo must be browsable anonymously (B2.4); rate limiting protects the open routes. |

## Consequences
- Module order in `services/pom.xml`: `common-events`, `common-messaging`, the three services, `api-gateway`.
- Each service imports the messaging configuration and enables scheduling for the publisher.
  *(Updated in step 2.2: the shared code reads and writes the `outbox` / `processed_events` tables with plain SQL,
  Spring's `JdbcClient`, instead of shared JPA entities. The lock query and the consumers' "insert if not seen before"
  are SQL anyway, and services then don't have to scan entities from another module.)*
- Polling costs one small query per service about every 500 ms (served by the existing partial index
  `idx_outbox_unpublished`) and adds up to ~0.5 s of delivery delay — well inside the 30 s saga timeout.
- At-least-once delivery means every consumer must be idempotent (proven by tests in step 2.3).
- With several instances of one service (HPA, Phase 4), `SKIP LOCKED` lets them publish in parallel without sending a
  row twice. Ordering per order then rests on the saga's causality: an order's next event is only created after its
  previous event was published and consumed. If that ever proves too weak, one active publisher per service (a lock)
  can be added without changing the outbox table.
