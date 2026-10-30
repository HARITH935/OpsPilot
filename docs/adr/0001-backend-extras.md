# ADR 0001 — Backend extras: GitHub OAuth, sliding-window rate limiting, control-plane cache, GraphQL

- **Status:** Accepted
- **Date:** 2026-10-30

## Context
OpsPilot already planned JWT auth, a basic Redis rate limiter on the gateway and a Redis cache for the product catalogue.
To make the backend stronger (and more realistic for a public site), we want four additions:
real third-party login, a fairer rate limiter, caching for the dashboard's heavy read queries, and a flexible read API
for incident history.

## Decision
1. **GitHub OAuth2 login (Phase 12)** in the API gateway via Spring Security OAuth2 Client (authorization-code flow).
   The gateway turns a GitHub login into its own RS256 JWT, stored in an `HttpOnly; Secure; SameSite=Lax` cookie.
   Logins listed in `ADMIN_GITHUB_LOGINS` become ADMIN; everyone else is VIEWER. Demo users stay for local dev + evals only.
   Anonymous visitors can watch; "Break it yourself" and Approve/Reject require login.
2. **Sliding-window rate limiter (Phase 2)** — custom, Redis sorted set + one Lua script (atomic). Keyed by JWT `sub`,
   falling back to client IP. Returns 429 with `Retry-After` and `X-RateLimit-*`. Fails open if Redis is down.
   Prometheus counter in Phase 2, Grafana panel in Phase 6, load-tested with Locust.
3. **Control-plane Redis cache (Phase 12)** — a second Redis (`redis-control`) used cache-aside by the GraphQL resolvers,
   TTL per query type, invalidated on every incident write using a version key. Never used for diagnosis data.
4. **Incident history GraphQL API (Phase 12)** — Strawberry on the agent's FastAPI app, read-only, cursor pagination,
   DataLoader, depth/alias limits. Writes stay REST; live updates stay WebSocket.
5. **Placement by dependency:** Redis runs in Docker from Phase 2 and moves to Kubernetes (own Helm chart, password from a Secret) in Phase 4.
   The data-plane Redis lives in `opspilot-apps` so chaos scenario C09 can target it without touching `opspilot-data`.

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Spring Cloud Gateway's built-in `RedisRateLimiter` (token bucket) | Less code, but allows bursts and has no `Retry-After`; the custom sliding window is fairer and a better learning exercise. |
| k6 for load tests | Works, but adds a tool; Locust is already in the stack. |
| One shared Redis for everything | Chaos C09 (Redis down) would also break the dashboard/agent cache — the "doctor" would go blind with the "patient". |
| JWT in `localStorage` | Readable by any injected script (XSS). An `HttpOnly` cookie is not. |
| OAuth in the Python agent (Authlib) | The gateway is the single front door and already issues JWTs; one auth point is simpler and safer. |
| REST-only incident history | Works, but the dashboard needs nested data (incident → evidence → hypotheses → timeline) in one request. |
| Caching the agent's investigation queries | Stale metrics could mislead diagnosis; correctness beats speed there. |

## Consequences
- **New libraries:** `spring-boot-starter-oauth2-client` (gateway), `strawberry-graphql` and `redis-py` (agent).
- **New infra:** one extra small Redis (~64–128 MB) in `opspilot-agent`; a GitHub OAuth App + its secret.
- **More work** in Phase 2 (limiter) and Phase 12 (three backend tasks before the UI).
- **New risks to manage:** GraphQL query abuse (mitigated by limits + rate limiting); cache invalidation bugs
  (mitigated by short TTLs + an acceptance test that updates must appear within 1 s); fail-open limiter allows
  unthrottled traffic while Redis is down (accepted: availability of the demo shop matters more; it is logged and counted).
