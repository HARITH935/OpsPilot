# deploy/k3s

Kubernetes clusters for OpsPilot. Today: the **local kind cluster** (Phase 4, [ADR 0009](../../docs/adr/0009-lean-local-kubernetes.md)).
The Oracle Cloud VM with k3s follows in Phase 5.

## The local cluster (kind, one node)
**kind** ("Kubernetes in Docker") runs each Kubernetes node as a Docker container. Ours has **one node**, which is the
control plane and the worker at the same time: three don't fit on an 8 GB Mac. The full three-node version runs in CI
(step 4.6).

**You need:** Docker Desktop with **5 GB** of memory (Settings → Resources → Advanced → Memory limit), `kind`,
`kubectl` (1.35–1.37, e.g. `brew install kubernetes-cli`) and `helm`. Stop the Compose stack first
(`docker compose -f deploy/docker/docker-compose.yml --profile apps down`): both together don't fit.

```bash
deploy/k3s/kind-up.sh                          # create: ~1 min (2 min the first time), ends with the node "Ready"
kubectl get pods -A                            # everything that runs
kubectl top node                               # CPU + memory (metrics-server)
kind delete cluster --name opspilot            # delete: 1 s, nothing is kept
```

| File | What it is |
|---|---|
| [`kind-config.yaml`](kind-config.yaml) | the cluster: one node, Kubernetes 1.36.4 (pinned by digest), ports 80 / 443 on 127.0.0.1 for the Ingress (step 4.5) |
| [`kind-up.sh`](kind-up.sh) | creates the cluster, applies the namespaces, installs metrics-server |
| [`metrics-server-values.yaml`](metrics-server-values.yaml) | metrics-server's settings (chart 3.14.0): small resources, `--kubelet-insecure-tls` (local only) |
| [`../k8s/namespaces.yaml`](../k8s/namespaces.yaml) | `opspilot-apps` and `opspilot-data`, with Pod Security rules |

**Pod Security.** Kubernetes checks every new pod against its namespace's level:
- `opspilot-apps` → **restricted**: no root, no privilege escalation, all Linux capabilities dropped, seccomp on. Our
  images were built for this (step 3.1).
- `opspilot-data` → **baseline** (blocks privileged pods, host network, ...) and warns about anything that wouldn't
  pass "restricted"; checked again when the database and Kafka operators arrive (steps 4.2 / 4.3).

Measured (2026-11-09, M2, Docker 4.8 GB): the empty cluster uses ~670 MB; a root pod in `opspilot-apps` is refused
("violates PodSecurity restricted:v1.36"), our order-service image from GHCR runs there as uid 10001.
