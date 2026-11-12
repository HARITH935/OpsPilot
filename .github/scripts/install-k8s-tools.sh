#!/usr/bin/env bash
# Installs the Kubernetes tools for the k8s workflow into a folder: kind, kubectl and Helm at fixed versions. Each
# download is refused unless it matches a SHA-256 written here, so a changed file on a download server fails the job
# instead of running in it (the same rule as Trivy in build-images.sh). The runner image brings its own kind / kubectl
# / helm, but at versions it chooses; Helm there may still be 3 (we use 4, ADR 0002).
# Usage: .github/scripts/install-k8s-tools.sh <folder>    (then put the folder first in PATH)
set -euo pipefail
BIN=${1:?usage: install-k8s-tools.sh <folder>}

KIND_VERSION=v0.33.0      # the kind used locally too (deploy/k3s/README.md)
# From the release's checksum file, compared with GitHub's own digest of the asset (both the same)
KIND_SHA256=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d
KUBECTL_VERSION=v1.36.6   # same minor version as the cluster (1.36.4); kubectl supports ± one
# From dl.k8s.io's checksum file
KUBECTL_SHA256=29818b6729dc9262b692307470d0558d027725e738be5dd47c760261868a2634
HELM_VERSION=v4.3.1       # same as locally
# From get.helm.sh's checksum file, compared with the one in the GitHub release notes (both the same)
HELM_SHA256=0403a88ae20913d11b10a2467b50087721fea7581736da6b9d3992f5357fe54b

download() {   # download <url> <file> <expected sha256>
  curl -fsSL --retry 5 --retry-delay 3 -o "$2" "$1"
  echo "$3  $2" | sha256sum --check --strict
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
download "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-linux-amd64" "$tmp/kind" "$KIND_SHA256"
download "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" "$tmp/kubectl" "$KUBECTL_SHA256"
download "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" "$tmp/helm.tar.gz" "$HELM_SHA256"
tar -xzf "$tmp/helm.tar.gz" -C "$tmp" linux-amd64/helm

mkdir -p "$BIN"
install -m 0755 "$tmp/kind" "$tmp/kubectl" "$tmp/linux-amd64/helm" "$BIN/"
"$BIN/kind" version
"$BIN/kubectl" version --client
"$BIN/helm" version --short
