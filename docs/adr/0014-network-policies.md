# ADR 0014 — NetworkPolicies: default-deny per namespace, allows live next to what they protect

- **Status:** Accepted
- **Date:** 2026-11-12

## Context
Part A §8 and §9 (B1/B8) call for "default-deny NetworkPolicies with explicit allows" in `opspilot-apps` and
`opspilot-data` (step 4.6b). A NetworkPolicy is a firewall rule for pods: once any policy selects a pod, that pod may
only send / receive what some policy allows — everything else, including traffic from another pod in the same
namespace, is refused. Two questions needed a decision: where the "deny everything" base rules live, and where the
specific allows for each piece of the shop live.

## Decision
1. **Base deny rules** (one `NetworkPolicy` per namespace, no allows) live in `deploy/k8s/network-policies.yaml` and
   are applied once by `deploy/k3s/kind-up.sh`, right after the namespaces are created — before any chart is
   installed, so nothing in the shop is ever briefly open by accident.
2. **The allows** for each piece live next to what they protect, as part of that component's own Helm chart:
   - `deploy/helm/opspilot/templates/network-policies.yaml` — Traefik → `api-gateway`, `api-gateway` → the three
     services, each service → the exact Kafka / Postgres / Redis ports it needs, DNS egress, and the `opspilot-obs`
     metrics scrape (Part B6).
   - `deploy/helm/postgres/templates/network-policy.yaml`, `deploy/helm/redis/templates/network-policy.yaml` — who
     may connect to that chart's pods, read from `values.yaml` (`networkPolicy.allowedApps`), so a chart stays
     self-contained and its README documents its own rules.
   - Strimzi (Kafka) manages its own pods' `NetworkPolicy` objects; `deploy/helm/kafka/values.yaml` only supplies the
     peers Strimzi should open 9092 to, instead of a separate hand-written policy duplicating what Strimzi already
     generates.
3. **`opspilot-apps`: deny both directions.** Every pod in the shop is both a possible target and a possible
   stepping stone, so both ingress and egress start closed, and each service's chart states exactly what it is
   allowed to call.
4. **`opspilot-data`: deny ingress only, leave egress open.** The CloudNativePG and Strimzi operators, and PostgreSQL
   itself, must reach the Kubernetes API server to watch and update `Cluster` / `Kafka` / `KafkaTopic` objects — an
   address that differs per cluster and isn't a pod `NetworkPolicy` can select cleanly. The risk this guard is for is
   who gets **into** the data layer, not what the data layer calls out to, so egress is left open there and the
   allows are all on ingress (plus one exception below).
5. **One path-specific exception:** the Kubernetes API server calls CloudNativePG's admission webhook (port 9443) to
   validate every change to a `Cluster` / `Database` object. The API server isn't a pod (it runs on the control
   plane's own network, invisible to `namespaceSelector`), so that one policy allows the port **from anywhere** — the
   narrowest rule that still works, not a blanket allow.
6. **Verification:** `deploy/k3s/network-policy-check.sh` runs an unlabelled "intruder" pod (locked down enough to
   pass Pod Security `restricted`) in `opspilot-apps` and in `default`, confirms neither can reach any part of the
   shop, then exercises each service's own allowed and disallowed paths (e.g. `payment-service` can reach neither
   Redis nor `order-service` directly — only Kafka). Wired into `kind-up.sh` for local use and into the `k8s` CI
   workflow, so a policy regression fails the required check instead of only showing up as a mysterious timeout.

## Alternatives considered
- **All NetworkPolicies in one place** (e.g. all in `deploy/k8s/` or all in the umbrella chart) — rejected: a chart's
  own access rules are part of what that chart is; keeping them with the Deployment/Service/values they describe
  means a chart stays understandable and testable on its own, the way `deploy/helm/postgres` and
  `deploy/helm/redis` already are for everything else.
- **Deny egress too in `opspilot-data`** — rejected for now: the operators' calls to the Kubernetes API can't be
  pinned to a stable, cluster-independent CIDR or Service selector without real risk of breaking CloudNativePG's or
  Strimzi's reconciliation loop. Revisit once the cloud cluster's control-plane address is known (Phase 5).
- **Hand-written Strimzi NetworkPolicy** instead of configuring Strimzi's own — rejected: Strimzi already generates
  and reconciles policies for its managed pods; a second, independent one would drift from Strimzi's and double the
  surface to keep in sync.

## Consequences
- A pod that isn't the shop's own code can no longer reach any shop port by just being in the same namespace —
  verified by the intruder check, not assumed.
- Known gap (unchanged from 4.6a/4.3): Kafka's listener still has no authentication or TLS, matching Compose; these
  NetworkPolicies are the only guard around it until Phase 5 adds TLS. Strimzi's own generated policies currently
  open 9092 to every pod in `opspilot-data` — narrowing that is future work, not blocking 4.6b, since the umbrella
  chart's own policy already restricts which `opspilot-apps` pods may reach it.
- `opspilot-data` egress stays open; closing it is deferred to Phase 5, once the cloud cluster's control-plane
  address is known and can be written into a narrow allow instead of "from anywhere".
