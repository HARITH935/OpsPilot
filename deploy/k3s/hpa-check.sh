#!/usr/bin/env bash
# Checks the HPAs (step 4.7a): under real load through the front door, at least one of order/inventory/payment
# scales past 1 replica, the load test itself passes (only correct 429s, checks.py), and the shop still gets every
# order to a final state (no order left PENDING, none timed out) once the load stops.
# Needs the whole shop running (deploy/k3s/shop-up.sh) and uv (loadgen/); runs in CI (k8s workflow), or locally:
#   deploy/k3s/hpa-check.sh
# Needs bash 4 or newer (associative arrays): CI's Ubuntu has it; macOS's own /bin/bash is 3.2 (brew install bash).
set -euo pipefail
cd "$(dirname "$0")/../.."
failed=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; failed=1; }

SERVICES=(order-service inventory-service payment-service)

# 1. metrics-server has data for every HPA before the load starts: each has "ScalingActive" = pods, requests and
#    a CPU reading are all there. Without this, the first minutes of load would scale on no information at all.
for svc in "${SERVICES[@]}"; do
  if kubectl wait "hpa/$svc" --namespace opspilot-apps --for=condition=ScalingActive --timeout=3m > /dev/null 2>&1; then
    ok "hpa/$svc: ScalingActive (metrics arrived)"
  else bad "hpa/$svc: never became ScalingActive within 3m"; fi
done
echo "Before load:"; kubectl get hpa --namespace opspilot-apps

# 2. The load test, through the front door like a real customer (not the gateway's own :8080 - there is no such
#    port from outside the cluster). Runs in the background so this script can watch the HPAs while it goes.
password=$(grep '^DEMO_USERS_PASSWORD=' deploy/docker/.env | cut -d= -f2-)
log=$(mktemp)
( cd loadgen && DEMO_USERS_PASSWORD="$password" uv run locust \
    --headless -u 60 -r 10 -t 4m --host http://localhost --only-summary ) > "$log" 2>&1 &
locust_pid=$!

# 3. While it runs: every 15 s, how many replicas each HPA is at and why (its current vs. target CPU%). The
#    highest replica count seen per service decides check 4 below.
declare -A max_replicas
for svc in "${SERVICES[@]}"; do max_replicas[$svc]=1; done
while kill -0 "$locust_pid" 2>/dev/null; do
  sleep 15
  echo "--- $(date +%T) ---"
  kubectl get hpa --namespace opspilot-apps --no-headers | while read -r name _ targets _ _ replicas _; do
    echo "  $name: $replicas replicas, $targets"
  done
  for svc in "${SERVICES[@]}"; do
    replicas=$(kubectl get "hpa/$svc" --namespace opspilot-apps -o jsonpath='{.status.currentReplicas}')
    (( replicas > max_replicas[$svc] )) && max_replicas[$svc]=$replicas
  done
done
echo "--- load finished, kubectl top pods ---"
kubectl top pods --namespace opspilot-apps 2>&1 || echo "(metrics not available right now)"

locust_exit=0
wait "$locust_pid" || locust_exit=$?   # "||" so set -e doesn't exit here before we can read the real code
echo "--- Locust log ---"; cat "$log"; rm -f "$log"
[ "$locust_exit" = 0 ] && ok "locust: PASSED (only correct 429s)" || bad "locust: FAILED, exit code $locust_exit"

# 4. At least one service scaled past 1 replica. Which one depends on where the load actually landed; not
#    requiring all three avoids a flaky check if, say, payment-service (little traffic by design) never crosses 70%.
scaled_any=0
for svc in "${SERVICES[@]}"; do
  echo "  max replicas seen for $svc: ${max_replicas[$svc]}"
  (( max_replicas[$svc] >= 2 )) && scaled_any=1
done
[ "$scaled_any" = 1 ] && ok "at least one HPA scaled above 1 replica" || bad "no HPA went above 1 replica under load"

# 5. Once the load is gone, every Deployment settles back to all its pods Ready (whatever replica count the HPA
#    is still holding) before we read the database below.
for svc in "${SERVICES[@]}" api-gateway; do
  if kubectl rollout status "deployment/$svc" --namespace opspilot-apps --timeout=5m > /dev/null 2>&1; then
    ok "deployment/$svc: all pods Ready"
  else bad "deployment/$svc: not all pods Ready within 5m"; fi
done

# 6. The saga's own safety net (30 s timeout) needs a moment to settle for anything still in flight when Locust
#    stopped, before we ask the database for a final answer.
sleep 35

# psql <database> <sql>: through the primary's local socket, the admin way (no network password - cluster.yaml).
# "-c postgres": the pod also has an init container (bootstrap-controller), so kubectl exec without -c prints a
# "Defaulted container ..." note on stderr every time - naming it avoids that note instead of merely hiding it.
# "|| echo ERROR": a failed exec (pod not found, connection refused) becomes a value the checks below report as a
# clean FAIL instead of aborting the script before anything after it runs; its real stderr still reaches the log.
psql() { kubectl exec --namespace opspilot-data postgres-1 -c postgres -- psql -d "$1" -tAc "$2" || echo ERROR; }

pending=$(psql orders "select count(*) from orders where status = 'PENDING'")
[ "$pending" = 0 ] && ok "orders: 0 left PENDING" || bad "orders: $pending (expected 0 PENDING; the saga never finished them, or the query itself failed)"

timed_out=$(psql orders "select count(*) from orders where status = 'CANCELLED' and cancel_reason = 'TIMEOUT'")
[ "$timed_out" = 0 ] && ok "orders: 0 cancelled for TIMEOUT" || bad "orders: $timed_out (expected 0 cancelled for TIMEOUT under load, or the query itself failed)"

negative_stock=$(psql inventory "select count(*) from products where stock < 0")
[ "$negative_stock" = 0 ] && ok "products: no negative stock" || bad "products: $negative_stock (expected 0 with negative stock, or the query itself failed)"

exit $failed
