# ADR 0009 — Phase 4: a one-node kind cluster locally, the full three-node cluster in CI

- **Status:** Accepted
- **Date:** 2026-11-09

## Context
The architecture plans a local **kind cluster with 3 nodes** running the whole system (B8, Phase 4). The development
machine is a MacBook Air M2 with **8 GB of RAM**; Docker Desktop had 3.8 GB. A rough budget for Phase 4 inside
Docker: Kubernetes itself ~0.7 GB, operators (CloudNativePG, Strimzi, Sealed Secrets) and Ingress ~1.2 GB, Postgres +
Kafka + Redis ~1.3 GB, the four services plus extra replicas under load ~1.8 GB: about 5 GB, before three nodes'
overhead. GitHub's standard runners have 16 GB.

## Decision
1. **Locally: kind with one node** (control plane and worker in one), `deploy/k3s/kind-config.yaml`, created by
   `deploy/k3s/kind-up.sh`. Docker Desktop gets **5 GB** (4.8 GB usable). Replicas and HPA maximums stay small.
2. **The full version in CI:** a workflow (step 4.6) creates a **three-node** kind cluster on GitHub's 16 GB runner,
   installs everything with Helm and runs orders through the Ingress. That is where "3 nodes" is proven.
3. **Kubernetes 1.36.4** (`kindest/node`, pinned by digest): one minor version behind the newest kind offers (1.37), so
   the operators of 4.2 / 4.3 support it. `kubectl` 1.37 from Homebrew (Docker Desktop's bundled 1.34 would be three
   versions apart; kubectl supports ± one).
4. **Pod Security Admission per namespace** (`deploy/k8s/namespaces.yaml`): `opspilot-apps` enforces **restricted**
   (our images already comply: user 10001, no capabilities, read-only root file system); `opspilot-data` enforces
   **baseline** and warns about **restricted** until the operators' pods are checked (4.2 / 4.3).
5. **metrics-server** from its Helm chart (3.14.0) with `--kubelet-insecure-tls`, local only (kind's kubelet has a
   self-signed certificate; the cluster listens only on 127.0.0.1). k3s brings its own metrics-server in the cloud.
6. The node's ports 80 / 443 are mapped to 127.0.0.1 from the start, for the Ingress in 4.5 (kind can't add them later).

## Alternatives considered
| Option | Why not chosen |
|---|---|
| kind with 3 nodes locally (the plan) | Doesn't fit: every node is another container with kubelet and containerd, on top of ~5 GB of workloads, on a Mac whose 8 GB are already used by macOS, the editor and Docker's VM. |
| The Oracle Cloud VM (24 GB) for Phase 4 already | Needs the Oracle account with a credit card now, free ARM capacity is often unavailable, and from Phase 5 the cloud cluster may only be changed through Git (Argo CD), so it would be set up twice. |
| Kubernetes only in CI | Nothing to set up locally, but no cluster to look at and try things on, which matters for learning and for interviews. Remains the fallback (below). |
| k3d / minikube locally | Not in the fixed stack; kind is, and the same kind runs in CI. |

## Consequences
- **Measured on 2026-11-09:** `kind-up.sh` 132 s the first time (node image download), 61 s after; `kind delete` 1 s.
  The empty cluster (Kubernetes' 10 system pods + metrics-server) uses **~670 MB**; `kubectl top node`: 658 MiB,
  0.26 CPU. Pod Security works: a root pod is refused with four reasons; our order-service image from GHCR runs under
  "restricted" as uid 10001.
- **The Mac is at its limit:** with the empty cluster and nothing else running, macOS had 5.8 GB in swap and swapped
  ~140 MB in and ~50 MB out within 30 idle seconds. Postgres (4.2) is light; Kafka (4.3) and the services will push the
  Docker VM to its limit. **Decision point after 4.2:** continue locally, or move the heavy steps to the CI cluster only.
- While the cluster runs, the Compose stack stays off (`docker compose … --profile apps down`), and the Mac runs as few
  other apps as possible.
- The three-node, full-size proof depends on step 4.6's CI workflow.
