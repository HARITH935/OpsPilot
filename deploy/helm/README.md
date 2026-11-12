# deploy/helm

OpsPilot's Helm charts: one per data component, and one generic chart that the umbrella chart `opspilot` uses for each
of the four services. A **Helm chart** is a
folder of Kubernetes YAML templates plus default settings (`values.yaml`); `helm upgrade --install` fills in the
templates and applies them as one **release**, which can be upgraded, tested and removed as a whole.

| Chart | What it installs | Since |
|---|---|---|
| [`postgres`](postgres/) | PostgreSQL 16 with a database + login per service, run by the CloudNativePG operator ([ADR 0010](../../docs/adr/0010-postgres-on-kubernetes.md)) | 4.2a |
| [`redis`](redis/) | one Redis 7 (memory only, password from a Secret) as a StatefulSet; installed as `redis` now, as `redis-control` in Phase 12 | 4.2b |
| [`kafka`](kafka/) | Kafka 4.2.2 (one KRaft node) and the 8 topics, run by the Strimzi operator ([ADR 0011](../../docs/adr/0011-kafka-on-kubernetes.md)) | 4.3 |
| [`service`](service/) | one Spring Boot service: Deployment, Service, ConfigMap, PodDisruptionBudget; a building block, not installed alone | 4.4 |
| [`opspilot`](opspilot/) | the umbrella: the four services, each a copy of `service` ([ADR 0012](../../docs/adr/0012-one-generic-service-chart.md)) | 4.4 |

## postgres
Needs the CloudNativePG operator and the logins' Secrets in the namespace: on the local cluster both come from
[`deploy/k3s`](../k3s/README.md) (`kind-up.sh`, then `secrets-from-env.sh`).

```bash
helm upgrade --install postgres deploy/helm/postgres -n opspilot-data
kubectl wait cluster/postgres -n opspilot-data --for=condition=Ready --timeout=6m   # Helm's --wait can't tell
kubectl get cluster,database,pods -n opspilot-data
helm test postgres -n opspilot-data && kubectl logs postgres-test-logins -n opspilot-data
kubectl exec -it postgres-1 -n opspilot-data -- psql                               # admin shell (local socket)
```

| File | What it is |
|---|---|
| [`values.yaml`](postgres/values.yaml) | instances (1), the image (16.15 "standard", pinned by digest, has pgvector), disk (1 GiB), memory, the three databases with their owners and Secrets |
| [`templates/cluster.yaml`](postgres/templates/cluster.yaml) | the CloudNativePG `Cluster`: bootstrap, the three logins (`managed.roles`), `pg_hba` rules (each database admits only its owner), no superuser login |
| [`templates/databases.yaml`](postgres/templates/databases.yaml) | one `Database` resource per service |
| [`templates/tests/test-logins.yaml`](postgres/templates/tests/test-logins.yaml) | the `helm test` pod: every login × database pair (3 ok, 6 refused), a wrong password, pgvector |

The services reach it at `postgres-rw.opspilot-data.svc:5432` ("rw" = the primary, for reads and writes).

**Check before a change:** `helm lint --strict deploy/helm/postgres` and `helm template postgres deploy/helm/postgres`
(shows the YAML the chart would apply).

## redis
Our own chart, no operator: a StatefulSet, two Services and a test, all written here. The release name names
everything, so the same chart serves the data-plane `redis` (opspilot-apps, now) and the control-plane `redis-control`
(opspilot-agent, Phase 12). Needs the Secret `redis` (key `password`) in the namespace: locally from
[`deploy/k3s/secrets-from-env.sh`](../k3s/secrets-from-env.sh).

```bash
helm upgrade --install redis deploy/helm/redis -n opspilot-apps
kubectl rollout status statefulset/redis -n opspilot-apps
helm test redis -n opspilot-apps && kubectl logs redis-test -n opspilot-apps
kubectl exec -it redis-0 -n opspilot-apps -- redis-cli                   # already logged in (REDISCLI_AUTH)
```

| File | What it is |
|---|---|
| [`values.yaml`](redis/values.yaml) | the image (7.4.12-alpine, the same as Compose, pinned by digest), the Secret, 64 MB + least-recently-used eviction, resources |
| [`templates/statefulset.yaml`](redis/templates/statefulset.yaml) | one pod `<release>-0`: non-root (999), read-only file system, no Kubernetes API token, three health checks that log in with the password |
| [`templates/services.yaml`](redis/templates/services.yaml) | `<release>` (the address clients use, fixed virtual IP) and `<release>-headless` (gives the pod its DNS name `<release>-0.<release>-headless`) |
| [`templates/tests/test-redis.yaml`](redis/templates/tests/test-redis.yaml) | the `helm test` pod: no / wrong / right password, write + read a key, memory limit, eviction policy, no snapshots, no append-only file |

Clients connect to `redis.opspilot-apps.svc:6379`. Design choices:
- **The password never shows.** It reaches Redis as configuration on standard input (a heredoc), not as a
  command-line argument. The pod spec only names the Secret. On the node, `ps` shows only `redis-server *:6379`.
- **Memory only, like Compose.** No disk and no snapshots, so a restart starts empty. That's fine for a cache and
  rate-limit counters: the gateway and inventory fail open (steps 2.9a / 2.10).
- **One replica, no PodDisruptionBudget.** With one copy, a PDB could only say "never take it down", which blocks node
  maintenance forever. A second copy would need Redis replication.
- **`exec redis-server`.** Redis is process 1, so it gets Kubernetes' stop signal itself: a pod is deleted in ~1 s
  instead of being killed after 30 s.

## kafka
Needs the Strimzi operator in the namespace: on the local cluster from [`deploy/k3s/kind-up.sh`](../k3s/kind-up.sh).
No Secrets: the listener is plain, with no login (like Compose).

```bash
helm upgrade --install kafka deploy/helm/kafka -n opspilot-data
kubectl wait kafka/kafka -n opspilot-data --for=condition=Ready --timeout=10m   # Helm's --wait can't tell
kubectl get kafka,kafkanodepool,kafkatopic,pods -n opspilot-data
helm test kafka -n opspilot-data && kubectl logs kafka-test -n opspilot-data
kubectl exec -it kafka-dual-role-0 -n opspilot-data -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

| File | What it is |
|---|---|
| [`values.yaml`](kafka/values.yaml) | Kafka version + metadata version, the image (Strimzi 1.3.0 + Kafka 4.2.2, pinned by digest), heap / disk / memory of the node, the Topic Operator's memory, the 8 topics (same list as [`topics.txt`](../docker/kafka/topics.txt)) |
| [`templates/kafka.yaml`](kafka/templates/kafka.yaml) | the `KafkaNodePool` (one node: controller + broker, KRaft) and the `Kafka` (plain listener 9092, broker settings, only the Topic Operator) |
| [`templates/topics.yaml`](kafka/templates/topics.yaml) | one `KafkaTopic` per topic: 3 partitions, 1 replica |
| [`templates/tests/test-kafka.yaml`](kafka/templates/tests/test-kafka.yaml) | the `helm test` pod, the checks of Compose's `smoke-test.sh`: 8 topics × 3 partitions, same key → same partition in order, a misspelled topic refused |

Clients connect to `kafka-kafka-bootstrap.opspilot-data.svc:9092`. Worth knowing:
- **Topics are Kubernetes objects.** `kubectl get kafkatopic` shows them; the Topic Operator creates, changes and
  deletes the real topics to match. Deleting a `KafkaTopic` (or `helm uninstall kafka`) deletes the topic and its messages.
- **Partitions can only grow.** Lowering `partitions` makes the `KafkaTopic` report `Decreasing partitions not supported`;
  the only way back is deleting and recreating the topic.
- **A config change restarts the broker.** Strimzi does it by itself (measured: 26 s), one pod at a time.

## opspilot (the four services)
The umbrella chart lists the generic chart [`service`](service/) four times as a **dependency**, each under its own
**alias** (`order-service`, `inventory-service`, `payment-service`, `api-gateway`). The alias becomes the service's name.
Each service's settings sit under its alias in [`opspilot/values.yaml`](opspilot/values.yaml), next to `global.image`
(the registry and the one image tag for all four).

```bash
deploy/k3s/shop-up.sh                                   # everything: Secrets, data layer, then the services (waits for each)
# or only the services, once the data layer runs:
helm dependency build deploy/helm/opspilot              # packs ../service into opspilot/charts/ (not in Git)
helm upgrade --install opspilot deploy/helm/opspilot -n opspilot-apps --wait --timeout 10m
helm test opspilot -n opspilot-apps && kubectl logs opspilot-test-order -n opspilot-apps
curl http://localhost/api/products                      # through the front door (Traefik + Gateway API, step 4.6a)
```

| File | What it is |
|---|---|
| [`service/values.yaml`](service/values.yaml) | what one service can set: image, port, replicas, `env` (plain settings), `secretEnv` (from Secrets), `secretFiles` (mounted Secrets), resources |
| [`service/templates/deployment.yaml`](service/templates/deployment.yaml) | the pods: user 10001, read-only file system + `/tmp`, no Kubernetes API token, startup / readiness / liveness on Spring Boot's health endpoints, a config fingerprint that rolls the pods when settings change |
| [`service/templates/configmap.yaml`](service/templates/configmap.yaml), [`service.yaml`](service/templates/service.yaml), [`pdb.yaml`](service/templates/pdb.yaml) | the plain settings; the fixed address `<name>:<port>`; at most one pod down during maintenance |
| [`opspilot/Chart.yaml`](opspilot/Chart.yaml) | the four dependencies (aliases of `service`), `Chart.lock` pins them |
| [`opspilot/values.yaml`](opspilot/values.yaml) | the four services' settings: the same as in Compose, with Kubernetes addresses (`postgres-rw.opspilot-data.svc`, `kafka-kafka-bootstrap.opspilot-data.svc:9092`, `redis`, `order-service:8081`, ...) |
| [`opspilot/templates/front-door.yaml`](opspilot/templates/front-door.yaml) | the front door ([ADR 0013](../../docs/adr/0013-traefik-and-gateway-api.md)): a `Gateway` (served by Traefik, port 80 of the node) and an `HTTPRoute` that sends `/api`, `/auth`, `/.well-known` to `api-gateway`; everything else gets 404 |
| [`opspilot/templates/tests/test-order.yaml`](opspilot/templates/tests/test-order.yaml) | the `helm test` pod: gateway ready, demo login + JWKS, an order of one SKU-6 → CONFIRMED, its payment COMPLETED, the stock one lower |

Passwords appear only as references to Secrets (`secretEnv`, `secretFiles`): `orders-db` / `inventory-db` /
`payments-db`, `redis`, `gateway-demo-users`, and the gateway's key pair `gateway-jwt`, all from
[`deploy/k3s/secrets-from-env.sh`](../k3s/secrets-from-env.sh).
