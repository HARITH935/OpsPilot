#!/usr/bin/env bash
# Checks the network rules (step 4.6b, ADR 0014): what must be closed is closed, and what the shop needs is open.
# Needs the whole shop running (deploy/k3s/shop-up.sh); runs in CI (k8s workflow), or locally:
#   deploy/k3s/network-policy-check.sh
# How: "intruder" pods without special labels (what an attacker who got into some pod would have) try to connect to
# the shop's parts and must fail; the services try their own connections, the needed ones must work, the others fail.
set -euo pipefail
cd "$(dirname "$0")/../.."
failed=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; failed=1; }

# The Redis image: it has busybox's nc (a tool that just opens a TCP connection), and it is already on the nodes
IMAGE=$(awk '/^image:/ {print $2}' deploy/helm/redis/values.yaml)
NAMESPACES=(opspilot-apps default)

cleanup() { for ns in "${NAMESPACES[@]}"; do kubectl delete pod intruder -n "$ns" --now --ignore-not-found > /dev/null 2>&1 || true; done; }
trap cleanup EXIT
# The intruder runs as a normal, locked-down pod (it must pass Pod Security "restricted" in opspilot-apps)
overrides='{"spec":{"automountServiceAccountToken":false,
  "securityContext":{"runAsNonRoot":true,"runAsUser":999,"seccompProfile":{"type":"RuntimeDefault"}},
  "containers":[{"name":"intruder","image":"'"$IMAGE"'","command":["sleep","600"],
    "securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}'
for ns in "${NAMESPACES[@]}"; do
  kubectl run intruder -n "$ns" --image="$IMAGE" --restart=Never --labels=app.kubernetes.io/name=intruder \
    --overrides="$overrides" > /dev/null
done
for ns in "${NAMESPACES[@]}"; do kubectl wait pod/intruder -n "$ns" --for=condition=Ready --timeout=2m > /dev/null; done

# connects <namespace> <host> <port>: can the intruder in that namespace open a TCP connection within 3 s?
intruder_connects() { kubectl exec -n "$1" intruder -- nc -z -w 3 "$2" "$3" > /dev/null 2>&1; }
# service_connects <service> <host> <port>: can that service's pod open one? (bash's /dev/tcp, in our images)
service_connects() { kubectl exec -n opspilot-apps "deploy/$1" -- bash -c "timeout 3 bash -c '</dev/tcp/$2/$3'" > /dev/null 2>&1; }

APPS=opspilot-apps.svc.cluster.local
DATA=opspilot-data.svc.cluster.local
TARGETS=("api-gateway.$APPS 8080" "order-service.$APPS 8081" "redis.$APPS 6379"
         "postgres-rw.$DATA 5432" "kafka-kafka-bootstrap.$DATA 9092")

# 1. Controls: the intruders themselves work (otherwise every "blocked" below would prove nothing)
if kubectl exec -n opspilot-apps intruder -- nslookup "api-gateway.$APPS" > /dev/null 2>&1; then
  ok "control: the intruder in opspilot-apps gets DNS answers (allowed for every pod)"
else bad "control: no DNS for the intruder in opspilot-apps"; fi
if intruder_connects default kubernetes.default.svc.cluster.local 443; then
  ok "control: the intruder in default reaches the Kubernetes API (no rules there)"
else bad "control: the intruder in default can't connect anywhere"; fi

# 2. Closed: neither intruder reaches any part of the shop
for ns in "${NAMESPACES[@]}"; do
  for target in "${TARGETS[@]}"; do
    read -r host port <<<"$target"
    if intruder_connects "$ns" "$host" "$port"; then bad "intruder in $ns reached ${host%%.*}:$port"
    else ok "intruder in $ns can't reach ${host%%.*}:$port"; fi
  done
done

# 3. Open: what each service needs; closed: what it doesn't
expect() {   # expect <open|closed> <service> <host> <port>
  if service_connects "$2" "$3" "$4"; then result=open; else result=closed; fi
  if [ "$result" = "$1" ]; then ok "$2 → ${3%%.*}:$4 $result"
  else bad "$2 → ${3%%.*}:$4 is $result, expected $1"; fi
}
expect open   api-gateway       "order-service.$APPS"         8081
expect open   api-gateway       "redis.$APPS"                 6379
expect open   inventory-service "redis.$APPS"                 6379
expect open   inventory-service "postgres-rw.$DATA"           5432
expect open   order-service     "kafka-kafka-bootstrap.$DATA" 9092
expect closed payment-service   "redis.$APPS"                 6379   # payment doesn't use Redis
expect closed payment-service   "order-service.$APPS"         8081   # the services talk via Kafka, not directly
expect closed order-service     "inventory-service.$APPS"     8083

exit $failed
