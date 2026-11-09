# deploy/helm

OpsPilot's Helm charts: one per component, plus an umbrella chart `opspilot` later (step 4.4). A **Helm chart** is a
folder of Kubernetes YAML templates plus default settings (`values.yaml`); `helm upgrade --install` fills in the
templates and applies them as one **release**, which can be upgraded, tested and removed as a whole.

| Chart | What it installs | Since |
|---|---|---|
| [`postgres`](postgres/) | PostgreSQL 16 with a database + login per service, run by the CloudNativePG operator ([ADR 0010](../../docs/adr/0010-postgres-on-kubernetes.md)) | 4.2a |

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
