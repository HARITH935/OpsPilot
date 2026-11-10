# deploy/k3s

Kubernetes clusters for OpsPilot. Today: the **local kind cluster** (Phase 4, [ADR 0009](../../docs/adr/0009-lean-local-kubernetes.md)).
The Oracle Cloud VM with k3s follows in Phase 5.

## The local cluster (kind, one node)
**kind** ("Kubernetes in Docker") runs each Kubernetes node as a Docker container. Ours has **one node**, which is the
control plane and the worker at the same time: three don't fit on an 8 GB Mac. The full three-node version runs in CI
(step 4.6).

**You need:** Docker Desktop with **5 GB** of memory (Settings → Resources → Advanced → Memory limit), `kind`,
`kubectl` (1.35–1.37, e.g. `brew install kubernetes-cli`), `helm` and `deploy/docker/.env` (copied from
`.env.example`, with real passwords). Stop the Compose stack first
(`docker compose -f deploy/docker/docker-compose.yml --profile apps down`): both together don't fit.

```bash
deploy/k3s/kind-up.sh                          # create: ~3.5 min (two operators), ends with the node "Ready"
deploy/k3s/secrets-from-env.sh                 # the Secrets, from deploy/docker/.env (same passwords as Compose)
helm upgrade --install postgres deploy/helm/postgres -n opspilot-data
kubectl wait cluster/postgres -n opspilot-data --for=condition=Ready --timeout=6m   # first time: a few minutes (image download)
helm test postgres -n opspilot-data            # logs in like a service: 11 checks
helm upgrade --install redis deploy/helm/redis -n opspilot-apps   # ready in ~10 s
helm test redis -n opspilot-apps               # password + settings: 9 checks
helm upgrade --install kafka deploy/helm/kafka -n opspilot-data
kubectl wait kafka/kafka -n opspilot-data --for=condition=Ready --timeout=10m      # first time: ~4.5 min
helm test kafka -n opspilot-data               # topics, keys, auto-create off: 13 checks
kubectl get pods -A                            # everything that runs
kubectl top node                               # CPU + memory (metrics-server)
kind delete cluster --name opspilot            # delete: 1 s, nothing is kept
```

| File | What it is |
|---|---|
| [`kind-config.yaml`](kind-config.yaml) | the cluster: one node, Kubernetes 1.36.4 (pinned by digest), ports 80 / 443 on 127.0.0.1 for the Ingress (step 4.5) |
| [`kind-up.sh`](kind-up.sh) | creates the cluster, applies the namespaces, installs metrics-server and the operators: CloudNativePG (PostgreSQL), Strimzi (Kafka) |
| [`metrics-server-values.yaml`](metrics-server-values.yaml) | metrics-server's settings (chart 3.14.0): small resources, `--kubelet-insecure-tls` (local only) |
| [`cnpg-values.yaml`](cnpg-values.yaml) | the CloudNativePG operator's settings (chart 0.29.1 = operator 1.30.1): image pinned by digest, watches only `opspilot-data` |
| [`strimzi-values.yaml`](strimzi-values.yaml) | the Strimzi operator's settings (chart 1.3.0): images pinned by digest, every Kafka pod set up for "restricted" |
| [`secrets-from-env.sh`](secrets-from-env.sh) | creates the Secrets from `deploy/docker/.env` (three database logins in `opspilot-data`, `redis` in `opspilot-apps`); refuses missing or `change-me` values; safe to run again |
| [`../k8s/namespaces.yaml`](../k8s/namespaces.yaml) | `opspilot-apps` and `opspilot-data`, with Pod Security rules |
| [`../helm/postgres`](../helm/README.md#postgres) | the PostgreSQL chart (step 4.2a) |
| [`../helm/redis`](../helm/README.md#redis) | the Redis chart (step 4.2b) |
| [`../helm/kafka`](../helm/README.md#kafka) | the Kafka chart (step 4.3) |

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
