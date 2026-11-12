#!/usr/bin/env bash
# Creates the Kubernetes cluster "opspilot" (kind) with OpsPilot's namespaces, metrics-server, the operators for
# PostgreSQL (CloudNativePG) and Kafka (Strimzi), and the front door (Gateway API + Traefik).
# Next: deploy/k3s/shop-up.sh (deploy/k3s/README.md).
# Locally one node (kind-config.yaml); CI uses three: KIND_CONFIG=deploy/k3s/kind-config-ci.yaml deploy/k3s/kind-up.sh
# Run from anywhere; needs Docker (5 GB of memory for Docker Desktop, ADR 0009), kind, kubectl and helm.
# Delete it again with: kind delete cluster --name opspilot
set -euo pipefail
cd "$(dirname "$0")/../.."

KIND_CONFIG=${KIND_CONFIG:-deploy/k3s/kind-config.yaml}
kind create cluster --config "$KIND_CONFIG" --wait 180s   # --wait: until the nodes are Ready
kubectl config use-context kind-opspilot                               # make sure kubectl talks to this cluster

kubectl apply -f deploy/k8s/namespaces.yaml
kubectl apply -f deploy/k8s/network-policies.yaml   # closed by default; the charts add the allows (step 4.6b)

# Each install waits until its pods are ready, at most 10 minutes: the first time, their images are downloaded, which
# on a slow connection takes minutes.

# metrics-server from its own chart repository, at a fixed chart version
helm upgrade --install metrics-server metrics-server \
  --repo https://kubernetes-sigs.github.io/metrics-server/ --version 3.14.0 \
  --namespace kube-system --values deploy/k3s/metrics-server-values.yaml --wait --timeout 10m

# The CloudNativePG operator (runs PostgreSQL for us, step 4.2a), also at a fixed chart version
helm upgrade --install cnpg cloudnative-pg \
  --repo https://cloudnative-pg.github.io/charts --version 0.29.1 \
  --namespace opspilot-data --values deploy/k3s/cnpg-values.yaml --wait --timeout 10m

# The Strimzi operator (runs Kafka for us, step 4.3); its chart is published as an OCI image on quay.io
helm upgrade --install strimzi oci://quay.io/strimzi-helm/strimzi-kafka-operator --version 1.3.0 \
  --namespace opspilot-data --values deploy/k3s/strimzi-values.yaml --wait --timeout 10m

# The Gateway API's object types (GatewayClass, Gateway, HTTPRoute, ...; step 4.6a, ADR 0013), v1.6.3: the version
# Traefik 3.7 is built against. Downloaded, refused unless it matches this SHA-256 (the same as GitHub's digest of
# the release file), then applied. --server-side: the file is too big for kubectl's client-side bookkeeping.
GATEWAY_API_VERSION=v1.6.3
GATEWAY_API_SHA256=356c2b8e286a964dc20995435659ad1095386329974793af10bbfe3eff977fe5
gateway_api=$(mktemp)
trap 'rm -f "$gateway_api"' EXIT
curl -fsSL --retry 5 --retry-delay 3 -o "$gateway_api" \
  "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GATEWAY_API_VERSION}/standard-install.yaml"
echo "${GATEWAY_API_SHA256}  ${gateway_api}" | shasum -a 256 -c
kubectl apply --server-side -f "$gateway_api"

# Traefik, the front door (step 4.6a): serves the Gateway API, listens on the node's port 80. In kube-system, like
# on k3s; --skip-crds: not Traefik's own object types, only the standard Gateway API above.
helm upgrade --install traefik traefik --repo https://traefik.github.io/charts --version 42.1.3 \
  --namespace kube-system --values deploy/k3s/traefik-values.yaml --skip-crds --wait --timeout 10m

kubectl get nodes -o wide
