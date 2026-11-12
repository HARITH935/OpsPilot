# ADR 0013 — The front door: Traefik and the Gateway API instead of Ingress-NGINX

- **Status:** Accepted
- **Date:** 2026-11-12

## Context
The architecture puts **Ingress-NGINX** in front of the cluster (B1, the tech stack, Phase 4 / 5). In November 2025
Kubernetes announced its retirement. Since March 2026 the project is **archived**: no more releases, bug fixes or
security patches. The Kubernetes Steering and Security Response Committees warn that staying on it leaves users open
to attack. The front door is the one component that faces the internet in Phase 5.

Kubernetes recommends the **Gateway API** as the successor; the Ingress API is frozen. **k3s**, the cloud cluster of
Phase 5, ships **Traefik v3** in `kube-system` as its built-in front door, and Traefik implements the Gateway API.

## Decision
1. **Traefik v3.7.16** (Helm chart `traefik` 42.1.3, image pinned by digest) is the front door, installed by
   `kind-up.sh` into **`kube-system`**, where k3s puts it too:
   - only its **Gateway API provider**; no Ingress, no Traefik-specific objects (`--skip-crds`), no dashboard
   - it listens inside its pod on 8000 (no root needed); a **hostPort 80** connects the node's port 80, which kind
     maps to `127.0.0.1:80`. Pod Security refuses hostPorts even at "baseline", which is one more reason for
     `kube-system`, a namespace without those rules (as on k3s).
   - it runs on the node labelled `ingress-ready` (in CI the control plane, with a toleration for its taint)
   - updates stop the old pod first (`maxSurge: 0`): only one pod can hold port 80 of a node
2. **The Gateway API's object types, v1.6.3** (the version Traefik 3.7 is built against), from the official release
   file, checked by SHA-256, applied server-side.
3. **In the umbrella chart (`templates/front-door.yaml`):**
   - a **Gateway** `opspilot` (class `traefik`, listener `http` on Traefik's entrypoint `web`), which takes routes
     from its own namespace only
   - an **HTTPRoute** that sends `/api`, `/auth` and `/.well-known` to `api-gateway:8080`. Everything else, including
     every service's `/actuator`, gets Traefik's 404 and stays inside the cluster.
   - The dashboard's route comes with the dashboard (Phase 12); HTTPS with cert-manager in Phase 5.
4. **Real client addresses.** Traefik replaces any `X-Forwarded-For` the client sends with the address it really saw
   (its default: no trusted sources). The gateway believes that header only from the pod network
   (`GATEWAY_TRUSTED_PROXIES=10.244.0.0/16` on kind; 10.42.0.0/16 on k3s). So the rate limiter counts each real
   client, and an address in a made-up header is ignored. With the NetworkPolicies (4.6b), only Traefik will be
   able to reach the gateway.
   - **Only our code reads that header:** `SERVER_FORWARDHEADERSSTRATEGY=none` in the gateway's settings. On
     Kubernetes, Spring Boot otherwise defaults `server.forward-headers-strategy` to `native`: Tomcat's RemoteIpValve
     then trusts **every private address** as a proxy and rewrites the client address before `ClientIpResolver`
     sees it. `GATEWAY_TRUSTED_PROXIES` was silently ignored, so any pod could fake its address. (Compose is not
     affected: there Spring Boot detects no cloud platform.)
5. **Proof from outside:** `deploy/k3s/front-door-check.sh` runs in the `k8s` workflow on the runner's localhost. It
   checks the routed paths, the 404s, an order to CONFIRMED, and which address the gateway counted.

## Alternatives considered
| Option | Why not chosen |
|---|---|
| Ingress-NGINX (the plan) | Archived since March 2026: known holes stay open, in the one public component. |
| Traefik with the classic Ingress API | Works and is simpler, but the Ingress API is frozen; the Gateway API is the recommended future and what k3s's Traefik supports too. |
| NGINX Gateway Fabric, Envoy Gateway, other Gateway API implementations | Maintained and good, but k3s already brings Traefik: a second front door in the cloud, or k3s's switched off. |
| Traefik's own `IngressRoute` objects | Traefik-only; the Gateway API objects work with any implementation. |

## Consequences
- **Found by the tests (step 4.6a):**
  - The first address check wrongly failed on the `helm test` pod's own, direct login; it now compares with
    Traefik's pod address.
  - A mutation without `GATEWAY_TRUSTED_PROXIES` changed nothing, which revealed Spring Boot's Kubernetes default
    above (`native`). After `SERVER_FORWARDHEADERSSTRATEGY=none` the same mutation is caught.
  - A mutation without the `/api` route was caught at once (404 for products and orders).
- The tech stack (CLAUDE.md §5) reads "Traefik (Gateway API)" instead of "Ingress-NGINX"; B1's "Ingress-NGINX + TLS"
  becomes "Traefik (Gateway API) + TLS". Phase 5's HTTPS: cert-manager with the Gateway (a `certificateRefs` on an
  HTTPS listener).
- In Phase 5, k3s's built-in Traefik is configured to the same settings (or replaced by this chart): decided there.
- Three objects to learn: GatewayClass (which controller), Gateway (where to listen), HTTPRoute (what goes where).
  The split matches roles: whoever runs the cluster owns the Gateway, each app owns its routes.
