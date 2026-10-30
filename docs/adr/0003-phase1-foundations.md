# ADR 0003 — Spring Boot 4.1, one Maven parent, one PostgreSQL with a database per service

- **Status:** Accepted
- **Date:** 2026-10-30

## Context
Phase 1 creates the first Java services. The original plan named Spring Boot 3.x, but start.spring.io now offers only
Spring Boot 4.x (default 4.1.2). Every starter the plan needs — Web, Data JPA, Flyway, PostgreSQL, Actuator, Validation,
Kafka, Data Redis, OAuth2 Client, Testcontainers, Spring Cloud Gateway, Resilience4j — supports 4.1.
The development machine has 8 GB RAM, so local infrastructure must stay small.

## Decision
1. **Spring Boot 4.1.x** for all Java services.
2. **One Maven parent project** at `services/pom.xml` with modules `common-events`, `order-service`, `inventory-service`,
   `payment-service` (and `api-gateway` in Phase 2), plus one Maven wrapper in `services/`.
3. **One PostgreSQL container with one database per service** (`orders`, `inventory`, `payments`), each with its own user.
   A service may only connect to its own database.

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Spring Boot 3.5 | No longer offered by start.spring.io; would need an upgrade soon. |
| Separate Maven project per service | `common-events` would have to be published or installed locally first; more build commands. |
| One PostgreSQL container per service | Same data isolation, but ~3× the memory on an 8 GB machine. |
| One shared database with schemas per service | Easier to accidentally join across services; weaker isolation. |

## Consequences
- `./mvnw verify` in `services/` builds and tests everything; one service runs with `./mvnw -pl order-service spring-boot:run`.
- Docker images (Phase 3) build from the `services/` folder so they can see the parent POM.
- In Kubernetes (Phase 4) the same layout maps onto one CloudNativePG cluster with three databases.
- Spring Boot 4 differs from many older tutorials, for example: Jackson 3 (`tools.jackson` packages), more modular
  starters, `@MockitoBean` instead of `@MockBean`, and Testcontainers 2 artifact names. We follow the 4.x docs.
