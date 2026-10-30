# order-service (:8081)

Orders API, order saga participant, transactional outbox.

## Run
Needs the local Postgres from `deploy/docker` running (see its README).
```bash
cd services
export DB_PASSWORD=$(grep '^ORDERS_DB_PASSWORD=' ../deploy/docker/.env | cut -d= -f2)
./mvnw -pl order-service spring-boot:run             # starts on :8081
SERVER_PORT=18081 ./mvnw -pl order-service spring-boot:run   # if 8081 is busy
```
On startup Flyway applies any new `db/migration/V*.sql` files, then Hibernate checks the entities match the tables.

## Test
```bash
cd services
./mvnw -pl order-service verify
```
Needs Docker running: repository and health tests start their own throwaway Postgres (Testcontainers).

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
| `DB_URL` | `jdbc:postgresql://localhost:5432/orders` | JDBC URL of the orders database |
| `DB_USERNAME` | `orders_user` | Database login |
| `DB_PASSWORD` | — (required) | Database password |

## Database
Tables (`src/main/resources/db/migration/V1__init.sql`): `orders`, `order_items`, `outbox`, `processed_events`.
Never edit a merged migration — add `V2__...sql`.

## Packages
`controller` → `service` → `repository` → database. `dto` = API shapes, `model` = JPA entities,
`event` = domain events / outbox, `config` = Spring config, `exception` = error handling.
