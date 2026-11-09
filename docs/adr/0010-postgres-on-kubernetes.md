# ADR 0010 — PostgreSQL on Kubernetes: CloudNativePG in opspilot-data, local Secrets from .env, Sealed Secrets in Phase 5

- **Status:** Accepted
- **Date:** 2026-11-09

## Context
Step 4.2 brings the data layer to the kind cluster. The architecture names CloudNativePG for PostgreSQL (B8) and Sealed
Secrets for credentials. Locally the database must look like the Compose one (ADR 0003): PostgreSQL 16, databases
`orders` / `inventory` / `payments`, each owned by its own login, which no other login may open. The local cluster is
thrown away and recreated often (`kind delete` takes 1 s, ADR 0009), and the Mac has little memory to spare.

A Sealed Secret is a Secret encrypted with a key that only exists inside one cluster: only that cluster can open it.

## Decision
1. **Step 4.2 is split:** 4.2a PostgreSQL (this ADR), 4.2b Redis as our own Helm chart.
2. **Sealed Secrets moves to Phase 5** (already its task 4 there). Locally, `deploy/k3s/secrets-from-env.sh` creates
   the Secrets from the gitignored `deploy/docker/.env`, the same passwords as Compose. Nothing secret goes into Git,
   and no password appears on a command line or on the screen.
3. **The CloudNativePG operator 1.30.1** (chart `cloudnative-pg` 0.29.1, image pinned by digest) is installed by
   `kind-up.sh` into **`opspilot-data`**, next to the database, not into its own `cnpg-system`. That keeps the namespace
   list of CLAUDE.md §8, and the chaos rule "never target `opspilot-data`" protects the operator too. It watches
   **only its own namespace** (`clusterWide: false`), so its rights to Secrets and Pods are a Role in `opspilot-data`,
   not a ClusterRole.
4. **Our chart `deploy/helm/postgres`** describes the database for the operator:
   - a `Cluster` with 1 instance and the image `postgresql:16.15-…-standard-trixie`, pinned by digest. "standard"
     includes pgvector, needed in Phase 11.
   - `enableSuperuserAccess: false`: the `postgres` superuser has no password, so nobody can log in as it over the
     network. Admins use `kubectl exec … psql` through the pod's local socket. `POSTGRES_PASSWORD` stays Compose-only.
   - the first database (`orders`) is created at bootstrap: without it, CloudNativePG would create an unused `app`.
     The three logins are `managed.roles`, with passwords from basic-auth Secrets, and the three databases are
     `Database` resources.
   - **`pg_hba` rules** instead of Compose's `REVOKE CONNECT`: each database admits its owner (`scram-sha-256`), and a
     final `reject` line refuses every other login to these databases.
   - a `helm test` pod that logs in like a service and checks all nine login × database pairs, a wrong password, and
     pgvector.
5. **`opspilot-data` enforces Pod Security "restricted"** (was "baseline", ADR 0009): the operator, PostgreSQL's
   bootstrap pod and PostgreSQL itself all pass it. Strimzi (4.3) will have to pass it as well.

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Sealed Secrets now, locally | The sealing key dies with every `kind delete`, so the sealed files in Git would only open in one throwaway cluster; making it work means backing up and restoring the key. It also costs memory on a Mac that is at its limit. In Phase 5 the cloud cluster lasts and Argo CD needs everything in Git: that is where Sealed Secrets earns its place. |
| Passwords as Helm values (`--set`) | They would end up in the shell history and in Helm's release record (a Secret holding all values); a chart that only *refers* to Secrets works the same with Sealed Secrets later. |
| Operator in its own namespace `cnpg-system`, watching all namespaces (the chart's default) | One more namespace to protect from chaos and to allow in NetworkPolicies, and wider rights than needed: everything that runs PostgreSQL lives in `opspilot-data`. |
| `DatabaseRole` resources (CloudNativePG's newer way) instead of `managed.roles` | Both work; `managed.roles` keeps the logins next to the `Cluster` they belong to, and the operator compares them with PostgreSQL whenever the `Cluster` changes (a `DatabaseRole` only when it itself changes). |
| `REVOKE CONNECT … FROM PUBLIC` via a bootstrap SQL script, as in Compose | `Database` resources are created after bootstrap, so the script can't reach them; `pg_hba` rules are part of the `Cluster` and are applied without a restart. |
| The "minimal" PostgreSQL image | Smaller, but without pgvector; Phase 11 would need a second image or extension images. |
| PostgreSQL 18 (CloudNativePG's default) | The architecture and Compose use 16; one version everywhere. |

## Consequences
- **Measured (2026-11-09, M2, Docker 4.8 GB):**
  - From nothing, `kind-up.sh` took 99 s, and the cluster was healthy with all three databases applied 405 s after
    the start. Most of that was downloading the 217 MB PostgreSQL image (4 min 21 s on this network; 2.5 min the
    first time).
  - A deleted PostgreSQL pod was replaced by the operator in ~13 s, and a table written before was still there (the
    data lives on a PersistentVolumeClaim).
  - Memory: operator 19 MiB, PostgreSQL 45 MiB at rest; the node went from ~660 MiB (empty) to ~1.1 GiB.
- **`helm test postgres`:** 11 checks pass. Two mutations were caught:
  - without the `reject` line, 6 checks fail (every login opens every database: PostgreSQL lets everyone connect by
    default)
  - with `trust` instead of `scram-sha-256`, the wrong-password check fails
- **Logins changed by hand stay changed until the next change to the `Cluster`:** a password set with `ALTER ROLE`
  was still changed after 6 minutes, and was put back 8 s after the `Cluster` resource changed. The CloudNativePG docs
  say "periodically"; in 1.30 the role check runs on changes to the `Cluster`.
- `helm upgrade --install --wait` returns before PostgreSQL is ready: Helm can't judge the operator's resources.
  Wait with `kubectl wait cluster/postgres --for=condition=Ready` instead.
- The services connect to `postgres-rw.opspilot-data.svc:5432` (4.4); they will need their passwords in
  `opspilot-apps` too, created the same way (step 4.4).
- Supersedes ADR 0009 point 4 for `opspilot-data` (baseline → restricted).
