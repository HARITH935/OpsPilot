# deploy/helm

OpsPilot's Helm charts: one per component, plus an umbrella chart `opspilot` later (step 4.4). A **Helm chart** is a
folder of Kubernetes YAML templates plus default settings (`values.yaml`); `helm upgrade --install` fills in the
templates and applies them as one **release**, which can be upgraded, tested and removed as a whole.

| Chart | What it installs | Since |
|---|---|---|
| [`postgres`](postgres/) | PostgreSQL 16 with a database + login per service, run by the CloudNativePG operator ([ADR 0010](../../docs/adr/0010-postgres-on-kubernetes.md)) | 4.2a |
| [`redis`](redis/) | one Redis 7 (memory only, password from a Secret) as a StatefulSet; installed as `redis` now, as `redis-control` in Phase 12 | 4.2b |

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
