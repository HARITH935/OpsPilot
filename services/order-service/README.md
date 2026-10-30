# order-service (:8081)

Orders API, order saga participant, transactional outbox.

## Run
```bash
cd services
./mvnw -pl order-service spring-boot:run             # starts on :8081
SERVER_PORT=18081 ./mvnw -pl order-service spring-boot:run   # if 8081 is busy
```

## Test
```bash
cd services
./mvnw -pl order-service verify
```

## Endpoints
| Path | Purpose |
|---|---|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/liveness` | "Is the process alive?" — Kubernetes restarts the pod if this fails |
| `GET /actuator/health/readiness` | "Can it take traffic?" — Kubernetes stops sending requests if this fails |
| `GET /actuator/prometheus` | Metrics for Prometheus |

## Configuration
| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8081` | HTTP port |

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = domain events / outbox, `config` = Spring config, `exception` = error handling.
