#!/usr/bin/env bash
# Builds the four service images for amd64 + arm64 and scans each one with Trivy. With --push, and only if every scan
# passed, it publishes them to GHCR as ghcr.io/harith935/opspilot-<service>:sha-<first 7 characters of the commit>.
# For CI (.github/workflows/images.yml): it sets up QEMU, a BuildKit builder and Trivy on a GitHub Ubuntu runner.
set -euo pipefail

PUSH=false
if [ "${1:-}" = "--push" ]; then PUSH=true; fi

REGISTRY=ghcr.io/harith935
TAG="sha-${GITHUB_SHA:0:7}"
SERVICES=(order-service inventory-service payment-service api-gateway)
PLATFORMS=linux/amd64,linux/arm64

# The helper images and the scanner, each pinned: a tag can later point to other code, a digest or checksum can't.
BINFMT="tonistiigi/binfmt:qemu-v10.2.3@sha256:400a4873b838d1b89194d982c45e5fb3cda4593fbfd7e08a02e76b03b21166f0"
BUILDKIT="moby/buildkit:v0.34.1@sha256:99368f3e46177c6405b9740e4461d91cee004128daeecca78d4ceac50ec1da5c"
TRIVY_VERSION=0.76.1
# From the release's checksum file, compared by hand with GitHub's own digest of the file (both the same)
TRIVY_SHA256=c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0

echo "::group::Set up QEMU, a BuildKit builder and Trivy"
# QEMU lets this amd64 runner run arm64 programs. Needed only for the small last stage of the Dockerfile:
# the jar is built once, natively ($BUILDPLATFORM, ADR 0008).
docker run --rm --privileged "$BINFMT" --install arm64
# A BuildKit builder in its own container: the runner's default builder can't build for two chips at once
docker buildx create --name opspilot --driver docker-container --driver-opt "image=$BUILDKIT" --use
docker buildx inspect --bootstrap
# Trivy straight from its GitHub release, refused unless it matches the checksum above (not the trivy-action, whose
# version tags were moved to credential-stealing code in March 2026, CVE-2026-33634)
curl -fsSL -o /tmp/trivy.tar.gz \
  "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"
echo "${TRIVY_SHA256}  /tmp/trivy.tar.gz" | sha256sum --check --strict
sudo tar -xzf /tmp/trivy.tar.gz -C /usr/local/bin trivy
trivy --version
echo "::endgroup::"

cd "$(dirname "$0")/../../services"
failed=()
for service in "${SERVICES[@]}"; do
  build=(docker buildx build --build-arg "SERVICE=$service" --label "org.opencontainers.image.revision=$GITHUB_SHA")

  echo "::group::$service: build for amd64 + arm64"
  "${build[@]}" --platform "$PLATFORMS" .   # both chips; the result stays in the builder's cache
  echo "::endgroup::"

  echo "::group::$service: scan (Trivy: vulnerabilities + secrets, HIGH and CRITICAL)"
  # The amd64 image from the cache into Docker, where Trivy reads it (arm64 has the same package versions)
  "${build[@]}" --platform linux/amd64 --load -t "scan/$service:$TAG" .
  if ! trivy image --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,secret --no-progress "scan/$service:$TAG"; then
    failed+=("$service")
  fi
  echo "::endgroup::"
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "::error::Trivy found HIGH or CRITICAL problems in: ${failed[*]} (nothing was published)"
  exit 1
fi

if $PUSH; then
  for service in "${SERVICES[@]}"; do
    echo "::group::$service: publish $REGISTRY/opspilot-$service:$TAG"
    # From the cache again: both chips under one name (a manifest list)
    docker buildx build --build-arg "SERVICE=$service" --label "org.opencontainers.image.revision=$GITHUB_SHA" \
      --platform "$PLATFORMS" --push -t "$REGISTRY/opspilot-$service:$TAG" .
    echo "::endgroup::"
  done
fi
