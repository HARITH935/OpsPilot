#!/usr/bin/env bash
# Creates the local Kubernetes cluster "opspilot" (kind, one node) with OpsPilot's namespaces, metrics-server and the
# PostgreSQL operator (CloudNativePG). Next: deploy/k3s/secrets-from-env.sh, then the postgres chart (deploy/k3s/README.md).
# Run from anywhere; needs Docker (5 GB of memory for Docker Desktop, ADR 0009), kind, kubectl and helm.
# Delete it again with: kind delete cluster --name opspilot
set -euo pipefail
cd "$(dirname "$0")/../.."

kind create cluster --config deploy/k3s/kind-config.yaml --wait 120s   # --wait: until the node is Ready
kubectl config use-context kind-opspilot                               # make sure kubectl talks to this cluster

kubectl apply -f deploy/k8s/namespaces.yaml

# metrics-server from its own chart repository, at a fixed chart version
helm upgrade --install metrics-server metrics-server \
  --repo https://kubernetes-sigs.github.io/metrics-server/ --version 3.14.0 \
  --namespace kube-system --values deploy/k3s/metrics-server-values.yaml --wait --timeout 3m

# The CloudNativePG operator (runs PostgreSQL for us, step 4.2a), also at a fixed chart version
helm upgrade --install cnpg cloudnative-pg \
  --repo https://cloudnative-pg.github.io/charts --version 0.29.1 \
  --namespace opspilot-data --values deploy/k3s/cnpg-values.yaml --wait --timeout 3m

kubectl get nodes -o wide
