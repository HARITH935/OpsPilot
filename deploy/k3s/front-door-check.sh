#!/usr/bin/env bash
# Checks the shop from OUTSIDE the cluster, through the front door (Traefik + Gateway API, step 4.6a), the way a
# customer comes in: http://localhost/ on the laptop or the CI runner (kind maps the node's port 80 there).
#   deploy/k3s/front-door-check.sh [base URL, default http://localhost]      (needs curl, python3, kubectl)
# Checks: the routed paths reach the gateway; everything else gets 404 (health and metrics stay inside); an order
# goes through the saga to CONFIRMED; the gateway sees the client's real address, not one the client made up.
set -euo pipefail
BASE=${1:-http://localhost}
failed=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; failed=1; }
status() { curl -s -o /dev/null -w '%{http_code}' -m 10 "$@" || true; }               # status <curl args...>
field() { python3 -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1" 2>/dev/null || true; }

# 1. A routed path reaches the gateway. Waits up to 60 s: Traefik picks up a new route within seconds.
for i in $(seq 1 60); do
  products=$(curl -s -m 10 "$BASE/api/products" || true)
  case "$products" in *SKU-1*) break ;; esac
  sleep 1
done
case "$products" in *SKU-1*) ok "GET /api/products through the front door" ;; *) bad "GET /api/products: '${products:0:200}'" ;; esac

# 2. Only the shop's paths are routed: the services' health and metrics, and anything else, get Traefik's 404
for path in /actuator/health /actuator/prometheus / /admin; do
  code=$(status "$BASE$path")
  [ "$code" = 404 ] && ok "$path: 404 (not routed)" || bad "$path: $code, expected 404"
done

# 3. An order, like a customer: with an Idempotency-Key, repeated with the same key after a 5xx (the order is never
#    made twice); then the saga's answer within 40 s
key="front-door-$(date +%s)"
order=""
for attempt in 1 2 3 4 5; do
  answer=$(curl -s -m 10 -w '\n%{http_code}' -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d '{"customerId":"front-door-check","items":[{"sku":"SKU-6","qty":1}]}' "$BASE/api/orders" || true)
  if [ "$(tail -n 1 <<<"$answer")" = 202 ]; then order=$(sed '$d' <<<"$answer" | field orderId); break; fi
  sleep 2
done
[ -n "$order" ] && ok "order placed: $order" || bad "POST /api/orders: $(tail -n 1 <<<"$answer")"
state=""
if [ -n "$order" ]; then
  for i in $(seq 1 40); do
    state=$(curl -s -m 10 "$BASE/api/orders/$order" | field status)
    case "$state" in CONFIRMED|CANCELLED) break ;; esac
    sleep 1
  done
fi
[ "$state" = CONFIRMED ] && ok "order CONFIRMED" || bad "order ended as '$state', expected CONFIRMED"

# 4. Whose address does the gateway count? A login attempt that claims to come from 203.0.113.7 (a documentation
#    address) in X-Forwarded-For. Traefik replaces that header with what it really saw; the gateway trusts the header
#    only from Traefik's pod network. The rate limiter's key for this attempt (lives a minute) shows the result.
code=$(status -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.7' \
  -d '{"username":"nobody","password":"not-a-password"}' "$BASE/auth/token")
[ "$code" = 401 ] && ok "login with wrong credentials: 401" || bad "login attempt: $code, expected 401"
#    Other recent login attempts can show up too (e.g. helm test's, made inside the cluster without Traefik), so the
#    check looks for the two wrong answers: the made-up address, and Traefik's own pod address.
keys=$(kubectl exec -n opspilot-apps redis-0 -- redis-cli --scan --pattern 'ratelimit:login:ip:*' 2>/dev/null || true)
addresses=$(sed 's/^ratelimit:login:ip://' <<<"$keys" | grep -v '^$' | sort -u | xargs)
traefik=$(kubectl get pods -n kube-system -l app.kubernetes.io/name=traefik -o jsonpath='{.items[*].status.podIP}')
echo "      the gateway counted login attempts from: ${addresses:-nobody} (Traefik's pod: ${traefik:-?})"
if [ -z "$addresses" ] || [ -z "$traefik" ]; then
  bad "no login attempt counted, or Traefik's pod not found"
elif [[ " $addresses " == *" 203.0.113.7 "* ]]; then
  bad "the gateway believed the made-up address 203.0.113.7"
elif [[ " $addresses " == *" $traefik "* ]]; then
  bad "the gateway counted Traefik's address ($traefik), not the client's"
else
  ok "the gateway counts the client's real address, not Traefik's or a made-up one"
fi

exit $failed
