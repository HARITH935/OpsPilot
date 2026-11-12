# deploy/k3s

Kubernetes clusters for OpsPilot (Phase 4, [ADR 0009](../../docs/adr/0009-lean-local-kubernetes.md)):
- the **local kind cluster**, one node, for looking around
- the **full cluster in CI**, three nodes, where every change to the charts is proven (see below)

The Oracle Cloud VM with k3s follows in Phase 5.

## The local cluster (kind, one node)
**kind** ("Kubernetes in Docker") runs each Kubernetes node as a Docker container. Ours has **one node**, which is the
control plane and the worker at the same time: three don't fit on an 8 GB Mac. The full three-node version runs in CI
(step 4.5, below).

**You need:** Docker Desktop with **5 GB** of memory (Settings → Resources → Advanced → Memory limit), `kind`,
`kubectl` (1.35–1.37, e.g. `brew install kubernetes-cli`), `helm` and `deploy/docker/.env` (copied from
`.env.example`, with real passwords). Stop the Compose stack first
(`docker compose -f deploy/docker/docker-compose.yml --profile apps down`): both together don't fit.

```bash
deploy/k3s/kind-up.sh                          # create: ~3.5 min (two operators), ends with the node "Ready"
deploy/k3s/shop-up.sh                          # the whole shop: Secrets, postgres, kafka, redis, the 4 services
helm test opspilot -n opspilot-apps            # an order through the gateway → CONFIRMED (7 checks)
kubectl port-forward -n opspilot-apps svc/api-gateway 8080:8080   # the shop on localhost:8080 (Ingress: step 4.6)
kubectl get pods -A                            # everything that runs
kubectl top node                               # CPU + memory (metrics-server)
docker stop opspilot-control-plane             # pause: frees the memory, keeps the cluster and its downloaded images
docker start opspilot-control-plane            # ...and back (~3 min until everything is ready again)
kind delete cluster --name opspilot            # delete: 1 s, nothing is kept (the next start downloads ~1 GB again)
```

Each part on its own (what `shop-up.sh` does, in this order):

```bash
deploy/k3s/secrets-from-env.sh                 # the Secrets, from deploy/docker/.env (same passwords as Compose)
helm upgrade --install postgres deploy/helm/postgres -n opspilot-data
kubectl wait cluster/postgres -n opspilot-data --for=condition=Ready --timeout=10m
helm upgrade --install kafka deploy/helm/kafka -n opspilot-data
kubectl wait kafka/kafka -n opspilot-data --for=condition=Ready --timeout=12m
helm upgrade --install redis deploy/helm/redis -n opspilot-apps --wait
helm dependency build deploy/helm/opspilot && helm upgrade --install opspilot deploy/helm/opspilot -n opspilot-apps --wait --timeout 10m
helm test postgres -n opspilot-data            # 11 checks; also: helm test redis -n opspilot-apps (9), helm test kafka -n opspilot-data (13)
```

| File | What it is |
|---|---|
| [`kind-config.yaml`](kind-config.yaml) | the cluster: one node, Kubernetes 1.36.4 (pinned by digest), ports 80 / 443 on 127.0.0.1 for the Ingress (step 4.6) |
| [`kind-up.sh`](kind-up.sh) | creates the cluster, applies the namespaces, installs metrics-server and the operators: CloudNativePG (PostgreSQL), Strimzi (Kafka) |
| [`metrics-server-values.yaml`](metrics-server-values.yaml) | metrics-server's settings (chart 3.14.0): small resources, `--kubelet-insecure-tls` (local only) |
| [`cnpg-values.yaml`](cnpg-values.yaml) | the CloudNativePG operator's settings (chart 0.29.1 = operator 1.30.1): image pinned by digest, watches only `opspilot-data` |
| [`strimzi-values.yaml`](strimzi-values.yaml) | the Strimzi operator's settings (chart 1.3.0): images pinned by digest, every Kafka pod set up for "restricted" |
| [`secrets-from-env.sh`](secrets-from-env.sh) | creates the Secrets from `deploy/docker/.env`: the three database logins (in `opspilot-data` and `opspilot-apps`), `redis`, `gateway-demo-users`; and the gateway's key pair `gateway-jwt` (made once with `openssl`, then kept); refuses missing or `change-me` values; safe to run again |
| [`shop-up.sh`](shop-up.sh) | the whole shop in order: Secrets, postgres, kafka, redis, then the four services (umbrella chart), waiting for each; always the charts' values (`--reset-values`); safe to run again (8 s when all runs) |
| [`../k8s/namespaces.yaml`](../k8s/namespaces.yaml) | `opspilot-apps` and `opspilot-data`, with Pod Security rules |
| [`../helm/postgres`](../helm/README.md#postgres) | the PostgreSQL chart (step 4.2a) |
| [`../helm/redis`](../helm/README.md#redis) | the Redis chart (step 4.2b) |
| [`../helm/kafka`](../helm/README.md#kafka) | the Kafka chart (step 4.3) |
| [`../helm/opspilot`](../helm/README.md#opspilot-the-four-services) | the four services: the umbrella chart over the generic chart `service` (step 4.4) |

**Pod Security.** Kubernetes checks every new pod against its namespace's level. Both namespaces enforce
**restricted**: no root, no privilege escalation, all Linux capabilities dropped, seccomp on.
- `opspilot-apps`: our images were built for this (step 3.1); Redis runs as its user 999 (step 4.2b).
- `opspilot-data`: the CloudNativePG operator and PostgreSQL pass it too (step 4.2a; baseline before), and so do
  Strimzi and Kafka (step 4.3, with Strimzi's "restricted" setting).

**Secrets.** On this cluster they come from your `.env` file and are never in Git. The cloud cluster gets Sealed
Secrets (encrypted, safe in Git) in Phase 5: a sealed Secret only opens in the cluster that sealed it, and this one is
thrown away often ([ADR 0010](../../docs/adr/0010-postgres-on-kubernetes.md)).

Measured (2026-11-09, M2, Docker 4.8 GB):
- The empty cluster uses ~670 MB. A root pod is refused ("violates PodSecurity restricted:v1.36"), and our
  order-service image from GHCR runs in `opspilot-apps` as uid 10001.
- With PostgreSQL (step 4.2a) the node uses ~1.1 GiB: operator 19 MiB, PostgreSQL 45 MiB at rest.
- From nothing to a healthy database took 405 s, most of it downloading the 217 MB PostgreSQL image. A deleted
  PostgreSQL pod is back in ~13 s, with its data.
- With PostgreSQL and Redis (step 4.2b) the node uses ~1.23 GiB; Redis itself 5 MiB. A deleted Redis pod is back as
  `redis-0` in 4 s (empty: memory only). Scaled to 0 and back, it was ready in 3 s.
- With Kafka (step 4.3) the node uses ~2.06 GiB: broker 466 MiB, Strimzi 271 MiB, Topic Operator 156 MiB. Docker
  as a whole ~2.5 of 4.8 GB, under the 4.3 GB rule of ADR 0009. `kind-up.sh` 204 s; Kafka ready 264 s later. A
  deleted broker pod is back in 23 s, with its topics and messages.
- With the whole shop (step 4.4, 2026-11-11) the node uses ~3.7 GiB (each service 256-359 MiB), Docker ~3.6 of 4.8 GB.
  On a slow connection (~190 KB/s that day) the first `shop-up.sh` took over an hour of downloads; a re-run on a running
  shop 8 s. `docker stop` took 4 s; after `docker start` everything was ready again in 197 s.

**The laptop's limit (step 4.4).** The memory rule (Docker under 4.3 GB) held, but with the whole shop running:
- macOS swapped ~7-8 GB in and out per 30 idle seconds
- the gateway logged route and Redis timeouts
- both operators kept restarting with "leader election lost"
- **Docker's VM clock fell behind the Mac** (measured: 8.5 minutes) and was later corrected with a jump. After such
  a jump the saga's timeout job sees every open order as old and cancels it (`TIMEOUT`), and leases expire.

The fresh runs passed every check; after a stop / start on the stressed laptop, orders were cancelled by such clock
jumps. Restarting Docker Desktop resets the clock (it also stops every other container).

## The full cluster in CI (three nodes)
The laptop can't run the whole shop reliably (step 4.4: heavy swapping, timeouts, Docker's clock jumping), so the
real proof runs on GitHub's 16 GB runner: [`.github/workflows/k8s.yml`](../../.github/workflows/k8s.yml), required
check `k8s`, on every pull request that changes `deploy/helm/`, `deploy/k3s/` or `deploy/k8s/`.

1. kind 0.33.0, kubectl 1.36.6 and Helm 4.3.1, from [`install-k8s-tools.sh`](../../.github/scripts/install-k8s-tools.sh)
   (fixed versions, each checked by SHA-256)
2. a `.env` with random, masked passwords (exists only on that runner, for that run)
3. `KIND_CONFIG=deploy/k3s/kind-config-ci.yaml deploy/k3s/kind-up.sh`: [three nodes](kind-config-ci.yaml), one control
   plane + two workers
4. `deploy/k3s/shop-up.sh`, then every chart's `helm test` (postgres, kafka, redis, opspilot)
5. our pods must run on the workers only (the control plane keeps kind's "no ordinary pods" taint), and Pod Security
   "restricted" must be clean
6. on failure: every pod's description and log, the events, and what ran where, as the artifact `k8s-logs` (7 days)

The images are the ones named in `deploy/helm/opspilot/values.yaml`, exactly what would be deployed. A change to the
services' code is tested by `e2e` (Compose), and reaches Kubernetes once CI has published its images and the tag is
bumped.
