#!/usr/bin/env bash
# Brings up the whole shop on the local cluster made by kind-up.sh: the Secrets, then the data layer (PostgreSQL,
# Kafka, Redis), then the four services, each waited for. Safe to run again (helm upgrade --install).
# --reset-values: always the values in the charts. Without it, an upgrade without new values keeps the values of the
# last release, including any `--set` given by hand (measured: a test mutation survived a "restore").
# Then check it with: helm test opspilot -n opspilot-apps
set -euo pipefail
cd "$(dirname "$0")/../.."

deploy/k3s/secrets-from-env.sh

# The data layer. PostgreSQL and Kafka are built by their operators, which Helm can't wait for: kubectl wait does.
# The limits are generous: on a fresh cluster the images (~1 GB in total) are downloaded first.
helm upgrade --install --reset-values postgres deploy/helm/postgres --namespace opspilot-data
helm upgrade --install --reset-values kafka deploy/helm/kafka --namespace opspilot-data
helm upgrade --install --reset-values redis deploy/helm/redis --namespace opspilot-apps --wait --timeout 10m
kubectl wait cluster/postgres --namespace opspilot-data --for=condition=Ready --timeout=10m
kubectl wait database --all --namespace opspilot-data --for=jsonpath='{.status.applied}'=true --timeout=3m
kubectl wait kafka/kafka --namespace opspilot-data --for=condition=Ready --timeout=12m
kubectl wait kafkatopic --all --namespace opspilot-data --for=condition=Ready --timeout=3m

# The four services: the umbrella chart, with the generic service chart packed into it first.
# --wait: until every Deployment's pods are ready (their readiness probes pass).
helm dependency build deploy/helm/opspilot
helm upgrade --install --reset-values opspilot deploy/helm/opspilot --namespace opspilot-apps --wait --timeout 10m
# The front door: Traefik has taken the Gateway ("Programmed") and the route (step 4.6a)
kubectl wait gateway/opspilot --namespace opspilot-apps --for=condition=Programmed --timeout=3m

kubectl get pods --namespace opspilot-apps
