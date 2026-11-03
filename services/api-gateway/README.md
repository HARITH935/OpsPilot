# api-gateway (:8080)

The single front door. Clients call only port 8080, and the gateway passes each request on to the service that owns
the path (a **reverse proxy**), then returns that service's answer unchanged. Built on Spring Cloud Gateway (the
Spring MVC version, [ADR 0005](../../docs/adr/0005-phase2-foundations.md)), with routes written in `application.yml`.
It has no database and no Kafka. It also checks **who is calling**: it issues and verifies JWT login tokens and
enforces the roles (step 2.8). Rate limiting (step 2.9) comes next. All of this is done once, here, instead of in
every service.

## Run
```bash
cd services
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
No Docker needed. The tests put small fake services (the HTTP server that comes with the JDK) on free local ports
and point the routes at them.

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

## When a service doesn't answer
The gateway's own errors use **RFC 9457 Problem Details** (`application/problem+json`), the same format as the services.

| Situation | Status | Body |
|---|---|---|
| No route for the path (e.g. `/api/shipments`) | **404** | `{"title":"Not Found","status":404,...}` |
| The service is down: connection refused, or not accepted within `GATEWAY_CONNECT_TIMEOUT` | **503** | `{"title":"Service unavailable","detail":"The service behind /api/products/SKU-2 can't be reached right now",...}` |
| The service accepted but didn't answer within `GATEWAY_READ_TIMEOUT` | **504** | `{"title":"Service too slow","detail":"The service behind /api/orders did not answer in time",...}` |

- The reply never names the service's internal address. The gateway log does, with the cause:
  `WARN … GET /api/products/SKU-2 failed (ConnectException): I/O error on GET request for "http://localhost:8083/…"`
- A refused connection fails at once (about 35 ms), so a stopped service costs callers no waiting.
- The timeouts are the same for every route for now. Per-route timeouts come with the circuit breakers in step 2.11
  (Resilience4j), because Gateway MVC 5.0 has no per-route timeout setting.
- The gateway's own health (`/actuator/health`) stays `UP` while a service is down: restarting the gateway wouldn't
  bring that service back.

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
| `GATEWAY_READ_TIMEOUT` | `2s` | Longest wait for a service's answer (then 504). With a tiny value even a refused connection runs out of time first and shows as 504 |
| `JWT_ISSUER` | `opspilot-gateway` | The `iss` claim; tokens with another issuer are refused |
| `JWT_TTL` | `30m` | How long a token is valid (1 s – 24 h) |
| `JWT_PRIVATE_KEY_FILE` / `JWT_PUBLIC_KEY_FILE` | — | PEM files of the signing key pair (both or neither); neither = a temporary key |
| `DEMO_USERS_ENABLED` | `false` | `true` turns on `POST /auth/token` for viewer / engineer / admin |
| `DEMO_USERS_PASSWORD` | — | Their shared password, 12+ characters; required when enabled (`deploy/docker/.env` locally) |

## Packages
- `config`: `HttpClientConfig` (HTTP/1.1), `SecurityConfig` (the rules), `JwtConfig` (the key pair, the token encoder and
  decoder), `JwtProperties` / `DemoUsersProperties` (the settings, checked at startup)
- `controller`: `AuthController` (`POST /auth/token`), `JwksController` (the public key)
- `service`: `TokenService` (makes signed tokens), `DemoUsers` (the three demo logins)
- `dto`: `TokenRequest` / `TokenResponse`
- `exception`: `GatewayExceptionHandler` (503 / 504, failed login), `SecurityProblemHandler` (401 / 403), all as Problem Details

The routes are not code: they are in `src/main/resources/application.yml`.
