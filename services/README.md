# Services

The Java 21 / Spring Boot 4.1 microservices (the "data plane"), built in Phases 1–2: one Maven parent (`pom.xml`)
with the modules `common-events`, `common-messaging`, `order-service`, `inventory-service`, `payment-service` and
`api-gateway`. How to run them: the [root README](../README.md#run-locally); each service has its own README.

## Container images
One [`Dockerfile`](Dockerfile) for all four services; `SERVICE` says which one. Build from this folder:
```bash
docker build --build-arg SERVICE=order-service -t opspilot/order-service:dev .   # or inventory-service, payment-service, api-gateway
```
No JDK or Maven needed on the machine: the jar is built inside Docker. The first build takes about a minute (Maven
downloads everything once and keeps it in a build cache), later ones 7–20 s. Tests don't run here; `./mvnw verify` and
CI run them. Why it's built this way: [ADR 0008](../docs/adr/0008-phase3-foundations.md).

**Three stages, one job each.** Only the last one becomes the image; the JDK, Maven and the sources stay behind.
| Stage | Base | What it does |
|---|---|---|
| `build` | `eclipse-temurin:21.0.13_9-jdk-noble` | `./mvnw -pl <service> -am -DskipTests package`: this service and the modules it uses |
| `layers` | (the build stage) | splits the jar into Spring Boot's layers (`java -Djarmode=tools … extract --layers`) |
| run | `eclipse-temurin:21.0.13_9-jre-noble` | a JRE, the layers, a non-root user; starts `java -jar app.jar` |

**Layers, least often changed first.** A new version of our code replaces only the last one:
| Layer | Size (order-service) | Changes when |
|---|---|---|
| Ubuntu 24.04 + JRE 21 | ~330 MB unpacked | the base image is updated |
| `dependencies` (Spring, Kafka, Hibernate, …) | 81 MB | a library version changes |
| `spring-boot-loader`, `snapshot-dependencies` | empty here | — |
| `application` (our classes + `common-*`) | 102 kB | every code change |

The image is 172 MB to download (compressed). Measured: one changed Java line → 10 of 11 layers byte-for-byte the same.

**Safe by default**
- Runs as user `opspilot`, id **10001**, not root. The code in `/app` belongs to root: the service can read it, not change it.
- Works with a read-only file system if `/tmp` is writable (Tomcat and Java write there):
  `docker run --read-only --tmpfs /tmp …`. Kubernetes will do the same in Phase 4.
- Heap up to **50%** of the container's memory limit (`-XX:MaxRAMPercentage=50`); the rest is for Java's memory
  outside the heap. More options: `-e JAVA_TOOL_OPTIONS=…`. With 512 MB the services use 236–329 MB each.

**Two chip types.** The `build` stage runs on the build machine's own chip (`--platform=$BUILDPLATFORM`): a jar is the
same everywhere, so only the small run stage is made per chip. An amd64 image builds on an arm64 Mac in 15 s:
```bash
docker buildx build --platform linux/amd64 --build-arg SERVICE=order-service -t opspilot/order-service:amd64 --load .
```

**Run one against the Docker stack** (its network is `opspilot_default`; inside it Kafka is `kafka:19092`):
```bash
DB_PASSWORD=$(grep '^ORDERS_DB_PASSWORD=' ../deploy/docker/.env | cut -d= -f2-) docker run --rm -p 18081:8081 \
  --network opspilot_default --read-only --tmpfs /tmp -m 512m -e DB_PASSWORD \
  -e DB_URL=jdbc:postgresql://postgres:5432/orders -e KAFKA_BOOTSTRAP_SERVERS=kafka:19092 opspilot/order-service:dev
```
`-e DB_PASSWORD` without a value passes the variable from your shell, so the password never appears in the command.
Step 3.2 adds all four services to `docker compose`.
