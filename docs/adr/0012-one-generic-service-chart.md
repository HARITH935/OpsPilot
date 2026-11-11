# ADR 0012 — The four services from one generic Helm chart, bundled by the umbrella chart opspilot

- **Status:** Accepted
- **Date:** 2026-11-11

## Context
B8 plans "one Helm chart per component + umbrella `opspilot` chart". Step 4.4 brings the four Spring Boot services
to Kubernetes. They were built from one Dockerfile (ADR 0008) and run the same way in Compose (one shared block of
settings). What differs between them is only:
- the image name and the port
- the plain settings (addresses, timeouts)
- which Secrets they read (database login, Redis, demo users, the gateway's key files)
- the gateway's smaller memory

## Decision
1. **One generic chart `deploy/helm/service`** contains, for one service:
   - a Deployment: user 10001, read-only file system plus `/tmp`, no Kubernetes API token, Pod Security
     "restricted"
   - startup / readiness / liveness probes on Spring Boot's health endpoints
   - a ConfigMap with the plain settings, and passwords as environment variables from Secrets (`secretEnv`)
   - mounted Secret files (`secretFiles`)
   - a Service, and a PodDisruptionBudget with `maxUnavailable: 1`
   - a checksum of the ConfigMap on the pod template, so a settings change rolls the pods
2. **The umbrella `deploy/helm/opspilot`** lists it four times as a dependency with an **alias** per service. The
   alias is the service's name (Deployment, Service, ConfigMap). The four services' settings and one `global.image`
   (registry + tag) sit in its `values.yaml`. Phase 5's CI bumps that one tag.
3. **The data layer stays outside the umbrella:** `postgres` and `kafka` in `opspilot-data`, `redis` in
   `opspilot-apps`, as separate releases. They live in another namespace, they hold data that an app uninstall or
   rollback must not touch, and B8 has Argo CD sync data operators by hand. `deploy/k3s/shop-up.sh` installs all of
   it in order and waits for each.
4. **Images:** the ones CI published from `main` (`ghcr.io/harith935/opspilot-<service>:sha-218a8ef`), by tag. They
   are our own and immutable by convention (CI never pushes a tag twice); third-party images stay pinned by digest.
5. **Secrets (local, from `secrets-from-env.sh`):**
   - the database logins are copied into `opspilot-apps` (a pod reads Secrets only from its own namespace)
   - `gateway-demo-users`
   - `gateway-jwt`: an RSA key pair made once with `openssl` and kept on later runs, so every gateway pod signs with
     the same key and tokens survive a re-run
6. **`helm test opspilot`:** a customer's visit through the gateway, from inside the cluster:
   - readiness, a demo login and the published key (JWKS)
   - an order of one SKU-6 → CONFIRMED, its payment COMPLETED, the stock one lower

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Four charts, one per service (B8 literally) | Four near-identical copies of the same ~150 lines: every fix (a probe, a security setting) made four times, and copies drift apart. A service that one day needs something the generic chart can't do can still get its own chart then. |
| A library chart with shared templates + four thin charts | Also no copies, but five charts instead of one plus a Helm feature more (library charts) to learn, for no difference in what gets deployed. |
| The data charts inside the umbrella too | `helm uninstall opspilot` or a bad rollback would take the databases with it; they also live in another namespace. |
| Pinning our images by digest | The tag is the git commit, which a reader can trace; CI never moves it. |

## Consequences
- **Measured (2026-11-11, M2, Docker 4.8 GB, a slow connection that day):**
  - All four services Ready, `helm test opspilot` 7/7: an order CONFIRMED after ~4–6 s, payment COMPLETED, stock one
    lower.
  - A rolling update of all four took 184 s with 0 restarts. Re-running `shop-up.sh` on a running shop took 8 s and
    changed nothing (Secrets "unchanged", the key pair kept).
  - Memory per service 256–359 MiB; the node 3.7 GiB, Docker ~3.6 of 4.8 GB.
- **Found while bringing it up, and fixed:**
  - **Service links.** Kubernetes adds variables for every Service in the namespace to each pod, e.g.
    `REDIS_PORT=tcp://10.96.x.x:6379`. Inventory and the gateway read `REDIS_PORT` as a number and crashed at start.
    Fix: `enableServiceLinks: false`, since the services find each other by DNS name.
  - **Slow starts.** With everything starting at once on the laptop, the JVMs needed over 3 minutes (killed by the
    first startup limit), and 58–99 s on the next try. The startup limit is now a setting (`startupSeconds`, 300 s);
    liveness stays strict after the start.
  - **Cold services are slower than the gateway's 2 s limit.** The first order after a start got a 504, yet the order
    was created. The test now behaves like a careful client: the order is sent with an `Idempotency-Key` and repeated
    with the same key after a 503 / 504 (the order service then returns the order it already made). Reads are
    repeated after a 5xx.
  - **`helm upgrade` without new values keeps the last values,** including a `--set` given by hand: a "restore" after
    a test mutation changed nothing. `shop-up.sh` now always passes `--reset-values`.
- **The test catches breakage:**
  - every payment failing → order CANCELLED, payment FAILED, stock unchanged (3 FAILs)
  - the gateway pointed at a wrong port → 503 five times with the same key (4 FAILs)
- **The shared key works:** a token issued by one gateway pod was accepted by its replacement (403 for a viewer
  writing products, 401 without a token).
- **No password is visible:** each of the five passwords was found 0 times in pods, Deployments, ConfigMaps and Helm's
  records.
- **The laptop is at its practical limit.** With the whole shop running, macOS swapped ~7–8 GB in and out per 30 idle
  seconds (after 4.3: ~0.1 GB), and the gateway logged route and Redis timeouts. The memory rule of ADR 0009
  (Docker > 4.3 GB) was not tripped.
- **Docker's VM clock** fell behind the Mac by up to 8.5 minutes under that load and was corrected with jumps. After a
  `docker stop` / `docker start` (back and ready in 197 s), two test orders were cancelled with `TIMEOUT`: one was
  "cancelled after an hour" by the VM's clock. The saga's safety net worked as designed, and the late payment was
  refunded (ADR 0006). The operators lost their leases ("leader election lost") 10-13 times.
- Adding a fifth service (e.g. the agent, Phase 9) is a block in `opspilot/values.yaml` and one dependency entry,
  if it fits the template.
- `helm dependency build deploy/helm/opspilot` must run before installing (it packs `../service` into
  `opspilot/charts/`, gitignored); `Chart.lock` is committed.
