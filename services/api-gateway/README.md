# api-gateway (:8080)

The single front door. Clients call only port 8080, and the gateway passes each request on to the service that owns
the path (a **reverse proxy**), then returns that service's answer unchanged. Built on Spring Cloud Gateway (the
Spring MVC version, [ADR 0005](../../docs/adr/0005-phase2-foundations.md)), with routes written in `application.yml`.
It has no database and no Kafka. Next: JWT login (step 2.8) and rate limiting (step 2.9), done once here instead of
in every service.

## Run
```bash
cd services
./mvnw -pl api-gateway spring-boot:run                                          # starts on :8080
ORDER_SERVICE_URL=http://localhost:18081 ./mvnw -pl api-gateway spring-boot:run   # order-service on 18081
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

## Packages
`config` = `HttpClientConfig` (HTTP/1.1), `exception` = `GatewayExceptionHandler` (503 / 504 as Problem Details).
The routes are not code: they are in `src/main/resources/application.yml`.
