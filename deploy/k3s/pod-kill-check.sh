#!/usr/bin/env bash
# Checks that killed pods come back (step 4.7b): Kubernetes replaces them on its own, and the shop works again after.
# Needs the whole shop running (deploy/k3s/shop-up.sh); runs in CI (k8s workflow), or locally:
#   deploy/k3s/pod-kill-check.sh [base URL, default http://localhost]      (needs curl, python3, kubectl, helm)
# Needs bash 4 or newer (associative arrays): CI's Ubuntu has it; macOS's own /bin/bash is 3.2 (brew install bash).
# Round 1 kills every pod of the four services at once, round 2 the data layer (PostgreSQL, Kafka, Redis) at once.
# After each round: how long until each part had a new Ready pod, and how long until an order was CONFIRMED again.
set -euo pipefail
cd "$(dirname "$0")/../.."
BASE=${1:-http://localhost}
failed=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; failed=1; }
field() { python3 -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1" 2>/dev/null || true; }

# What gets killed: <name> → "<namespace> <how kubectl finds its pods>". A label selector for the Deployments
# (all their pods, however many the HPA keeps), the pod's own name for the single-pod parts of the data layer.
declare -A WHERE=(
  [api-gateway]="opspilot-apps --selector=app.kubernetes.io/name=api-gateway,app.kubernetes.io/instance=opspilot"
  [order-service]="opspilot-apps --selector=app.kubernetes.io/name=order-service,app.kubernetes.io/instance=opspilot"
  [inventory-service]="opspilot-apps --selector=app.kubernetes.io/name=inventory-service,app.kubernetes.io/instance=opspilot"
  [payment-service]="opspilot-apps --selector=app.kubernetes.io/name=payment-service,app.kubernetes.io/instance=opspilot"
  [postgres]="opspilot-data --field-selector=metadata.name=postgres-1"   # CloudNativePG's only instance
  [kafka]="opspilot-data --selector=strimzi.io/cluster=kafka,strimzi.io/name=kafka-kafka"   # Strimzi's broker pod
  [redis]="opspilot-apps --field-selector=metadata.name=redis-0"         # the StatefulSet's pod
)
declare -A BACK   # name → how long from the kill until a new pod was Ready (the summary at the end)
KILLED_AT=0       # when the last round's kill happened (seconds since 1970), for the order check after it

# pods_of <namespace> <how>: one line per pod, "uid|deletion time|Ready". A replacement of a StatefulSet or
# CloudNativePG pod has the SAME name as the one it replaces, so "new" is decided by the uid, not the name.
pods_of() {
  # shellcheck disable=SC2086   # $2 is meant to split (e.g. "--selector=..."), it never contains spaces
  kubectl get pods -n "$1" $2 -o jsonpath='{range .items[*]}{.metadata.uid}|{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}'
}

# kill_round <name>...: deletes all their pods at the same moment, then waits (5 min at most) until each has a NEW pod
# that is Ready and not itself being deleted. Sets KILLED_AT. (Called directly, never as $(kill_round ...): that would
# run it in a subshell, and its FAILs and times would be lost.)
kill_round() {
  declare -A old start
  local name ns how
  for name in "$@"; do
    read -r ns how <<<"${WHERE[$name]}"
    old[$name]=" $(pods_of "$ns" "$how" | cut -d'|' -f1 | xargs) "
    # shellcheck disable=SC2086
    kubectl delete pod -n "$ns" $how --wait=false > /dev/null   # don't wait for the old pods to finish stopping
    start[$name]=$(date +%s)
    echo "      killed $name: $(wc -w <<<"${old[$name]}" | xargs) pod(s)"
  done
  KILLED_AT=$(date +%s)
  local deadline=$(( $(date +%s) + 300 )) waiting=("$@") still
  while (( ${#waiting[@]} > 0 )) && (( $(date +%s) < deadline )); do
    sleep 2
    still=()
    for name in "${waiting[@]}"; do
      read -r ns how <<<"${WHERE[$name]}"
      if pods_of "$ns" "$how" | awk -F'|' -v old="${old[$name]}" \
           'index(old, " " $1 " ") == 0 && $2 == "" && $3 == "True" { found = 1 } END { exit !found }'; then
        BACK[$name]="$(( $(date +%s) - start[$name] )) s"
        ok "$name: a new pod Ready ${BACK[$name]} after the kill"
      else
        still+=("$name")
      fi
    done
    waiting=("${still[@]}")
  done
  for name in "${waiting[@]}"; do BACK[$name]="not within 5 min"; bad "$name: no new Ready pod within 5 min"; done
}

# order_once: one order through the front door (SKU-7: helm test counts SKU-6 and must not see ours), then its
# final status within 40 s (the saga's timeout is 30 s). Prints CONFIRMED, CANCELLED, or what went wrong.
order_once() {
  local key answer code id state=""
  key="pod-kill-$(date +%s)-$RANDOM"   # a new order each attempt, never a replay of an earlier one
  answer=$(curl -s -m 10 -w '\n%{http_code}' -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d '{"customerId":"pod-kill-check","items":[{"sku":"SKU-7","qty":1}]}' "$BASE/api/orders" || true)
  code=$(tail -n 1 <<<"$answer")
  if [ "$code" != 202 ]; then echo "not placed (HTTP $code)"; return; fi
  id=$(sed '$d' <<<"$answer" | field orderId)
  for _ in $(seq 1 40); do
    state=$(curl -s -m 5 "$BASE/api/orders/$id" | field status)
    case "$state" in CONFIRMED|CANCELLED) break ;; esac
    sleep 1
  done
  echo "${state:-still PENDING after 40 s}"
}

# orders_work_again <kill time> <round>: an order every few seconds until one is CONFIRMED, 5 min after the kill at
# most. The time it took is the honest recovery number: the pods being Ready isn't the end, the services also have to
# reconnect (to PostgreSQL, Kafka, Redis) and Kafka has to hand out the partitions to the new consumers again.
orders_work_again() {
  local since=$1 attempt=0 result
  while (( $(date +%s) - since < 300 )); do
    attempt=$((attempt + 1))
    result=$(order_once)
    if [ "$result" = CONFIRMED ]; then
      ok "$2: an order CONFIRMED again $(( $(date +%s) - since )) s after the kill (attempt $attempt)"
      return
    fi
    echo "      attempt $attempt: $result"
    sleep 5
  done
  bad "$2: no order CONFIRMED within 5 min of the kill"
}

# Round 1: the four services. The HPA may hold more than one pod of a service: all of them go at once.
echo "Round 1: the four services"
kill_round api-gateway order-service inventory-service payment-service
orders_work_again "$KILLED_AT" "round 1"

# Round 2: the data layer, all three at once. The services stay up and must find their way back to it.
echo "Round 2: PostgreSQL, Kafka, Redis"
kill_round postgres kafka redis
for what in cluster/postgres kafka/kafka; do   # and their operators agree they are healthy again
  if kubectl wait "$what" -n opspilot-data --for=condition=Ready --timeout=5m > /dev/null 2>&1; then
    ok "$what: Ready"
  else bad "$what: not Ready within 5 min"; fi
done
orders_work_again "$KILLED_AT" "round 2"

# Finally the whole shop's own test: login, an order, its payment, the stock (step 4.4)
if helm test opspilot -n opspilot-apps --timeout 6m > /dev/null 2>&1; then
  ok "helm test opspilot after both rounds"
else
  bad "helm test opspilot after both rounds:"; kubectl logs opspilot-test-order -n opspilot-apps 2>&1 || true
fi

echo "Killed → a new Ready pod after:"
for name in api-gateway order-service inventory-service payment-service postgres kafka redis; do
  printf '      %-18s %s\n' "$name" "${BACK[$name]:-?}"
done
exit $failed
