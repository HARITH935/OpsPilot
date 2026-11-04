# api-gateway (:8080)

The single front door. Clients call only port 8080, and the gateway passes each request on to the service that owns
the path (a **reverse proxy**), then returns that service's answer unchanged. Built on Spring Cloud Gateway (the
Spring MVC version, [ADR 0005](../../docs/adr/0005-phase2-foundations.md)), with routes written in `application.yml`.
It has no database and no Kafka. It also checks **who is calling**: it issues and verifies JWT login tokens and
enforces the roles (step 2.8), and it limits **how often**: a sliding-window rate limiter in Redis (step 2.9).
Each route also protects itself from a slow or broken service: a timeout, a circuit breaker and retries (step 2.11a).
All of this is done once, here, instead of in every service.

## Run
Needs the local Redis from `deploy/docker` for rate limiting (see its README); without it the gateway still works,
unlimited (fail-open).
```bash
cd services
export REDIS_PASSWORD=$(grep '^REDIS_PASSWORD=' ../deploy/docker/.env | cut -d= -f2-)
./mvnw -pl api-gateway spring-boot:run                                          # starts on :8080
ORDER_SERVICE_URL=http://localhost:18081 ./mvnw -pl api-gateway spring-boot:run   # order-service on 18081

# With the demo users (viewer / engineer / admin), password from deploy/docker/.env (see "Login and roles")
DEMO_USERS_ENABLED=true DEMO_USERS_PASSWORD=$(grep '^DEMO_USERS_PASSWORD=' ../deploy/docker/.env | cut -d= -f2-) \
  ORDER_SERVICE_URL=http://localhost:18081 ./mvnw -pl api-gateway spring-boot:run
```
It starts in under 2 s and uses about 150 MB. It starts even with no service running: a missing service only
affects its own route (503).

## Test
```bash
cd services
./mvnw -pl api-gateway verify
```
Needs Docker running: the full-gateway tests start their own throwaway Redis (Testcontainers), so they never touch
the Redis in `deploy/docker`. In place of the services, they put small fake services (the HTTP server that comes with
the JDK) on free local ports and point the routes at them.

## Routes
| Path | Goes to | Set with |
|---|---|---|
| `/api/orders/**` | order-service | `ORDER_SERVICE_URL` (default `http://localhost:8081`) |
| `/api/products/**` | inventory-service | `INVENTORY_SERVICE_URL` (default `http://localhost:8083`) |
| `/api/payments/**` | payment-service | `PAYMENT_SERVICE_URL` (default `http://localhost:8082`) |

`**` also matches nothing, so `/api/orders` goes to order-service too. The method, path, query string, headers and
body are passed on unchanged, and so is the answer (status, headers such as `Location`, body), errors included: a
404 or 422 from a service reaches the client as that service wrote it.
```bash
curl 'localhost:8080/api/products?size=3'
curl -i -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: try-1' -d '{"customerId":"c-1","items":[{"sku":"SKU-1","qty":2}]}'
curl localhost:8080/api/orders/<orderId>           # the Location header from the POST works here as it is
curl 'localhost:8080/api/payments?status=COMPLETED'
```
The services keep their own ports, so they can still be called directly while developing. From Phase 4 only the
gateway is reachable from outside the cluster.

## Login and roles (JWT)
A **JWT** (JSON Web Token) is a signed note: who you are (`sub`), your roles, and when the note expires. The gateway
signs it with its **private key** (RS256); anyone can check it with the **public key**, but nobody can change it or
make one. The client sends it with each request as `Authorization: Bearer <token>`, so the gateway keeps no sessions.

| Endpoint | What it does |
|---|---|
| `POST /auth/token` `{"username","password"}` | **200** `{"access_token":"eyJ…","token_type":"Bearer","expires_in":1800}` · 401 wrong username or password (the same answer for both) · 400 a field missing. **Only with `DEMO_USERS_ENABLED=true`**, otherwise 404 |
| `GET /.well-known/jwks.json` | The public key (a JWK Set, RFC 7517), so other services can check our tokens. Never contains the private key |
| `GET /.well-known/oauth-protected-resource` | A standard description of how to send tokens here (RFC 9728); the 401 answers point to it |

A token's claims: `{"iss":"opspilot-gateway","sub":"demo:admin","roles":["ADMIN"],"iat":…,"exp":…}`, valid 30 min
(`JWT_TTL`). Its header names the key id (`kid`) that signed it.

**Demo users** (local development and evals only; real people log in with GitHub in Phase 12):

| User | Role | `POST /api/products` |
|---|---|---|
| `viewer` | `VIEWER` | 403 |
| `engineer` | `ENGINEER` | 403 |
| `admin` | `ADMIN` | allowed |

All three share `DEMO_USERS_PASSWORD`, which lives only in `deploy/docker/.env` (never in Git). If
`DEMO_USERS_ENABLED=true` and the password is missing or shorter than 12 characters, the gateway **refuses to start**,
so switching it on by mistake can't hand out ADMIN tokens.
```bash
export DEMO_PW=$(grep '^DEMO_USERS_PASSWORD=' deploy/docker/.env | cut -d= -f2-)   # run from the repo root
TOKEN=$(curl -s -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$DEMO_PW\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
curl -i -X POST localhost:8080/api/products -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"sku":"SKU-12","name":"Desk Lamp","price":19.99,"stock":10}'   # 201 (409 the 2nd time); no token: 401
```

**Rules** (ADR 0005), checked by Spring Security before a request reaches any route:

| Request | Rule |
|---|---|
| `GET` / `HEAD /api/products/**` | open |
| any other method on `/api/products/**` (today `POST`) | **ADMIN** token |
| everything else (orders, payments, login, public key, health) | open |

| Situation | Status | `title` |
|---|---|---|
| no token where one is needed | **401** + `WWW-Authenticate: Bearer …` | `Login required` |
| token broken, expired, from another issuer, or signed with another key | **401** + `WWW-Authenticate: Bearer error="invalid_token"…` | `Invalid token` |
| valid token, role not enough | **403** + `WWW-Authenticate: Bearer error="insufficient_scope"…` | `Not allowed` |

- A token that is sent is **always checked**, even on an open path: a bad token is an error, not "anonymous".
- Changing anything in a token (e.g. `VIEWER` → `ADMIN` in its claims) breaks its signature → 401.
- The `Authorization` header is passed on to the services unchanged; they don't check it. Locally their own ports
  can still be called directly. In Kubernetes only the gateway is reachable from outside (Phase 4).
- **The signing key:** set `JWT_PRIVATE_KEY_FILE` and `JWT_PUBLIC_KEY_FILE` to PEM files (in the cloud: a Kubernetes
  Secret mounted as files, Phase 5). Both files must be one key pair, or the gateway refuses to start. Without them
  the gateway makes a **temporary key** at startup and logs a WARN: fine locally, but every token stops working when
  the gateway restarts. Make a key pair:
  ```bash
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem   # keep this one secret
  openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
  ```
- No CSRF protection is needed yet: the token travels in a header, which another website can't make a browser add.
  Phase 12 moves it into a cookie, and that is looked at again then.

## Rate limiting
Every request to `/api/**` and `/auth/**` is counted against its caller's **budget**. Once the budget is used up, the
answer is **429 Too Many Requests**, and the request never reaches a service.

| Who | Counted by | Default budget, per route | Setting |
|---|---|---|---|
| a caller with a valid token | the token's `sub` (e.g. `demo:admin`) | **20** requests per 1 s | `RATE_LIMIT_USER_REQUESTS` |
| a caller without a token | IP address | **10** requests per 1 s | `RATE_LIMIT_ANONYMOUS_REQUESTS` |
| `POST /auth/token` (login) | IP address | **10** tries per **1 min** (password guessing) | `RATE_LIMIT_LOGIN_REQUESTS` |

- Each route (`orders`, `products`, `payments`) has its own budget. Any other `/api/...` path shares one budget, `other`,
  so a client can't escape the limit (or create endless metric labels) by inventing paths.
- **Never limited:** `/actuator/**` (health checks, metrics) and `/.well-known/**` (the public key).
- **Sliding window:** "at most 10 in any 1-second stretch", not "10 per calendar second". With fixed windows a client
  could send 10 at 0.99 s and 10 more at 1.01 s. Each caller is a Redis sorted set holding the times of its counted
  requests. One Lua script (`src/main/resources/ratelimit/sliding-window.lua`) drops the ones older than the window,
  counts the rest, and adds the new request only if it fits.
  - Redis runs a script **as one step**, so two requests at the same moment can't both take the last place. A test
    sends 50 requests at the same moment against a limit of 20: exactly 20 get through.
  - The time comes from Redis (`TIME`), so every copy of the gateway counts with the same clock.
  - A refused request is not counted, and a key disappears one window after its last request.
- **The IP address** is the address the connection comes from. `X-Forwarded-For` is believed only when the
  connection comes from a proxy listed in `GATEWAY_TRUSTED_PROXIES` (addresses or CIDR ranges, comma-separated; none
  by default, the Ingress in Phase 4). Then the rightmost address that isn't one of our proxies counts. Otherwise a
  client could claim a new address with every request and get a fresh budget each time.

Every limited answer carries the client's standing; a 429 also says when to come back:
```
HTTP/1.1 429
X-RateLimit-Limit: 10            the budget
X-RateLimit-Remaining: 0         left in the window
X-RateLimit-Reset: 1             seconds until a place frees up
Retry-After: 1                   (429 only) wait this many seconds
{"title":"Too many requests","status":429,"detail":"At most 10 requests per 1 s here without logging in. Try again in 1 s.","instance":"/api/products/SKU-1"}
```

**Fail-open:** if Redis can't be reached, requests go through **unlimited** rather than failing. The shop keeps
working, and the outage shows in three places:
- the log: one WARN at most every 30 s, and an INFO when Redis is back
- the metric `gateway_ratelimit_redis_errors_total`
- the answers, which then carry no `X-RateLimit-*` headers

Redis commands time out after 100 ms. While the connection is down, they are refused at once (`RedisConfig`): after
a `docker stop`, 30 requests took 0.4 s with that setting and 4.1 s without it. Limiting comes back by itself once
Redis does (4 s after `docker start` in the real run). Redis being down doesn't make the gateway unhealthy: its
health check ignores Redis on purpose.

**Metrics** (at `/actuator/prometheus`): `gateway_ratelimit_rejected_total{route,key_type}` (429s, by route and by
`user` / `ip`), `gateway_ratelimit_redis_errors_total`.

**Under load:** the Locust test in [`loadgen/`](../../loadgen/README.md) sends 40 shoppers at the gateway for 30 s.
In the real run 191 of 1128 requests got a correct 429 (all anonymous, all on products), nothing else failed, and
`gateway_ratelimit_rejected_total` rose by exactly 191.

## When a service doesn't answer
The gateway's own errors use **RFC 9457 Problem Details** (`application/problem+json`), the same format as the services.

| Situation | Status | Body |
|---|---|---|
| No route for the path (e.g. `/api/shipments`) | **404** | `{"title":"Not Found","status":404,...}` |
| The service is down: connection refused, or not accepted within `GATEWAY_CONNECT_TIMEOUT` (after the retries, for GET / HEAD) | **503** | `{"title":"Service unavailable","detail":"The service behind /api/products/SKU-2 can't be reached right now",...}` |
| The route's circuit breaker is open | **503** + `Retry-After` | `{"title":"Service unavailable","detail":"The service behind /api/products/SKU-2 failed too often just now, so it gets a short break. Try again later.",...}` |
| The service didn't answer within the route's timeout (`GATEWAY_<ROUTE>_TIMEOUT`) | **504** | `{"title":"Service too slow","detail":"The service behind /api/orders did not answer in time",...}` |

- The reply never names the service's internal address. The gateway log does, with the cause:
  `WARN … GET /api/products/SKU-2 failed (ConnectException): I/O error on GET request for "http://localhost:8083/…"`
- A refused connection fails at once (about 35 ms). With the retries of a GET it's about 0.8–1 s in all.
- The gateway's own health (`/actuator/health`) stays `UP` while a service is down: restarting the gateway wouldn't
  bring that service back.

## Resilience: timeout, circuit breaker, retries (per route)
Every route has the filter `Resilience=<route>` in `application.yml` (`RouteResilience`, Resilience4j 2.3).
From the outside in:

```
Retry ( CircuitBreaker ( TimeLimiter ( the call to the service ) ) )
```

**Timeout** (`TimeLimiter`): each route waits at most its own time for an answer, 2 s by default
(`GATEWAY_ORDERS_TIMEOUT`, `GATEWAY_PRODUCTS_TIMEOUT`, `GATEWAY_PAYMENTS_TIMEOUT`); then **504**. The call runs on a
virtual thread of its own, so the request can stop waiting at the limit, and the call is interrupted. Gateway MVC
5.0 has no per-route timeout setting, so this is how routes get different ones. `GATEWAY_READ_TIMEOUT` (30 s) is
only a backstop: keep it longer than every route timeout.

**Circuit breaker**, one per route, like a fuse:

| State | What happens | Until |
|---|---|---|
| `CLOSED` (normal) | calls go through; the last 20 are counted | 50% of them failed (at least 20 calls seen) → `OPEN` |
| `OPEN` | every call is refused at once (**503** + `Retry-After: 30`), the service isn't called | 30 s passed (`GATEWAY_CIRCUIT_OPEN_FOR`) → `HALF_OPEN` |
| `HALF_OPEN` | 3 test calls go through | enough of them fine → `CLOSED`; too many fail → `OPEN` again |

- **Failures:** no answer (refused, too slow) and a 5xx answer (500, 502, 503, 504). A 4xx is not: that's the
  caller's mistake, not the service's. A service's own 5xx answer is still passed on unchanged.
- An open breaker gives a broken service time to recover, and callers a quick answer instead of a slow one.
- Every state change is logged (`WARN … Circuit breaker products: CLOSED → OPEN …`, then `INFO` for `HALF_OPEN` and
  `CLOSED`). Other routes are not affected.

**Retries**, for **GET and HEAD only** (repeating a POST could create a second order):
- only when the service **couldn't be reached** (refused, connect timeout) or answered **502 / 503** ("can't take
  it right now"). Not after a 500 ("tried and failed"), not after a timeout (the request may still be running
  there), not while the breaker is open.
- up to 3 retries, about 100 → 200 → 400 ms apart, each **± 50% at random** (jitter): clients that failed together
  don't all come back at the same moment. Every attempt is logged (`INFO … Route products: retry 2 in 195 ms …`).
- every attempt passes the circuit breaker, so a stopped service fills the breaker's window 4 times as fast, and the
  breaker opens sooner.
- Gateway MVC's own `Retry` filter has no jitter, and its `CircuitBreaker` filter turns every other error into a plain
  500 (a stopped service would no longer be 503) and drops a service's own 5xx answer. So the route filter is our own
  (`ResilienceFilterFunctions`), registered through a `FilterSupplier` bean.

**Metrics** (at `/actuator/prometheus`), each with `name="<route>"`: `resilience4j_circuitbreaker_state{state}`
(1 = the current one), `resilience4j_circuitbreaker_calls_seconds_count{kind="successful|failed"}`,
`resilience4j_circuitbreaker_not_permitted_calls_total`, `resilience4j_circuitbreaker_failure_rate`,
`resilience4j_retry_calls_total{kind}`, `resilience4j_timelimiter_calls_total{kind="successful|failed|timeout"}`.

**From the real run (2026-11-04):**
- inventory stopped, one GET every 0.5 s: each was tried 4 times (waits 73 / 195 / 560 ms, 84 / 284 / 543 ms, …)
  and answered 503 after 0.8–1.0 s. During the 5th request the breaker opened (19 of the last 20 calls failed);
  from then on 503 + `Retry-After: 30` in 8–24 ms, inventory not called.
- inventory started: `OPEN → HALF_OPEN` exactly 30 s after opening, 3 test calls 200, `HALF_OPEN → CLOSED`.
- payments pointed at a fake that answers after 3 s, `GATEWAY_PAYMENTS_TIMEOUT=1s`: 504 after 1.3 s, not retried,
  while orders answered 200 in 0.3 s.
- order-service stopped: 21 POSTs, **0 retries**. After 19 failed POSTs (20 calls with one earlier GET that worked) the
  breaker opened; the last 2 POSTs were refused at once. With `GATEWAY_CIRCUIT_OPEN_FOR=5s`: `HALF_OPEN` 5 s later,
  `CLOSED` after 3 test calls.

## HTTP client
The gateway calls the services with Java's built-in `java.net.http.HttpClient`, chosen by Spring Boot and configured
by `spring.http.clients.*`. `HttpClientConfig` pins it to HTTP/1.1. Otherwise it would ask every service to switch to
HTTP/2 on each request (`Upgrade: h2c`), which the services don't support. Requests run on virtual threads
(`spring.threads.virtual.enabled`), so a request waiting for a slow service costs almost nothing.

## Operational endpoints
| Path | Purpose |
|---|---|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/liveness` | "Is the process alive?" — Kubernetes restarts the pod if this fails |
| `GET /actuator/health/readiness` | "Can it take traffic?" — Kubernetes stops sending requests if this fails |
| `GET /actuator/prometheus` | Metrics for Prometheus |

## Configuration
| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `ORDER_SERVICE_URL` | `http://localhost:8081` | Where `/api/orders/**` goes |
| `INVENTORY_SERVICE_URL` | `http://localhost:8083` | Where `/api/products/**` goes |
| `PAYMENT_SERVICE_URL` | `http://localhost:8082` | Where `/api/payments/**` goes |
| `GATEWAY_CONNECT_TIMEOUT` | `1s` | Longest wait for a service to accept the connection (then 503) |
| `GATEWAY_READ_TIMEOUT` | `30s` | Backstop for a service's answer (then 504). The route timeouts below are shorter and decide; keep this one longer than all of them |
| `GATEWAY_ORDERS_TIMEOUT` / `GATEWAY_PRODUCTS_TIMEOUT` / `GATEWAY_PAYMENTS_TIMEOUT` | `2s` each | Each route's own timeout (then 504); 10 ms – 1 min |
| `GATEWAY_CIRCUIT_OPEN_FOR` | `30s` | How long an open circuit breaker refuses calls before letting test calls through |
| `JWT_ISSUER` | `opspilot-gateway` | The `iss` claim; tokens with another issuer are refused |
| `JWT_TTL` | `30m` | How long a token is valid (1 s – 24 h) |
| `JWT_PRIVATE_KEY_FILE` / `JWT_PUBLIC_KEY_FILE` | — | PEM files of the signing key pair (both or neither); neither = a temporary key |
| `DEMO_USERS_ENABLED` | `false` | `true` turns on `POST /auth/token` for viewer / engineer / admin |
| `DEMO_USERS_PASSWORD` | — | Their shared password, 12+ characters; required when enabled (`deploy/docker/.env` locally) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Where Redis is |
| `REDIS_PASSWORD` | — (empty) | Redis password (`deploy/docker/.env` locally) |
| `RATE_LIMIT_ENABLED` | `true` | `false` switches rate limiting off |
| `RATE_LIMIT_USER_REQUESTS` | `20` | Requests per 1 s per user, per route |
| `RATE_LIMIT_ANONYMOUS_REQUESTS` | `10` | Requests per 1 s per IP without a token, per route |
| `RATE_LIMIT_LOGIN_REQUESTS` | `10` | Login tries per 1 min per IP |
| `GATEWAY_TRUSTED_PROXIES` | — (none) | Proxies whose `X-Forwarded-For` is believed, e.g. `10.0.0.0/8` |

## Packages
- `config`: `HttpClientConfig` (HTTP/1.1), `SecurityConfig` (the rules), `JwtConfig` (the key pair, the token encoder and
  decoder), `RedisConfig` (fail fast while Redis is down, the Lua script), `ResilienceConfig` (the circuit breakers,
  time limiters and retries, their metrics and log lines), `JwtProperties` / `DemoUsersProperties` /
  `RateLimitProperties` / `ResilienceProperties` (the settings, checked at startup)
- `filter`: `RateLimitFilter` (runs after Spring Security, before the routes: counts, adds the headers, answers 429),
  `RouteResilience` (timeout + circuit breaker + retries around each route's call), `ResilienceFilterFunctions`
  (makes it the `Resilience` filter in `application.yml`)
- `controller`: `AuthController` (`POST /auth/token`), `JwksController` (the public key)
- `service`: `TokenService` (makes signed tokens), `DemoUsers` (the three demo logins), `SlidingWindowRateLimiter`
  (runs the Lua script), `ClientIpResolver` (whose IP is it)
- `dto`: `TokenRequest` / `TokenResponse`
- `exception`: `GatewayExceptionHandler` (503 / 504, open circuit, route timeout, failed login), `SecurityProblemHandler` (401 / 403),
  `ProblemResponses` (writes a Problem Details body from code that runs before Spring MVC)

The routes are not code: they are in `src/main/resources/application.yml`.
