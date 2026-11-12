#!/usr/bin/env bash
# Creates the Kubernetes Secrets of the local kind cluster from deploy/docker/.env: the same passwords as the Compose
# stack (the three database logins, Redis, the demo users), plus the gateway's JWT key pair. Nothing secret goes into
# Git, onto a command line or onto the screen. The cloud cluster gets Sealed Secrets instead (Phase 5, ADR 0010).
# Run after kind-up.sh (deploy/k3s/shop-up.sh does); running it again updates the Secrets and keeps the key pair.
set -euo pipefail
cd "$(dirname "$0")/../.."

ENV_FILE=${ENV_FILE:-deploy/docker/.env}   # another file can be given: ENV_FILE=/path/to/file deploy/k3s/secrets-from-env.sh
if [[ ! -f $ENV_FILE ]]; then
  echo "missing $ENV_FILE: copy deploy/docker/.env.example to it and set real values" >&2
  exit 1
fi

# Prints the value of $1 from the .env file; fails if it is missing or still the example's "change-me"
env_value() {
  local value
  value=$(grep "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2-) || true
  if [[ -z $value || $value == change-me ]]; then
    echo "set $1 in $ENV_FILE" >&2
    return 1
  fi
  printf '%s' "$value"
}

# Every value is read first: one missing value stops the script before anything is created
orders_password=$(env_value ORDERS_DB_PASSWORD)
inventory_password=$(env_value INVENTORY_DB_PASSWORD)
payments_password=$(env_value PAYMENTS_DB_PASSWORD)
redis_password=$(env_value REDIS_PASSWORD)
demo_users_password=$(env_value DEMO_USERS_PASSWORD)
if (( ${#demo_users_password} < 12 )); then   # the gateway refuses to start with a shorter one
  echo "DEMO_USERS_PASSWORD in $ENV_FILE needs at least 12 characters" >&2
  exit 1
fi

# One login per service database, as a Secret "<database>-db" (type basic-auth = the keys "username" and "password").
# In opspilot-data for CloudNativePG (it reads Secrets only from its own namespace; the label cnpg.io/reload makes it
# apply a changed password at once), and the same in opspilot-apps for the service that logs in with it.
db_secret() {
  local namespace=$1 db=$2 password=$3
  # create --dry-run only writes the Secret as YAML; apply then creates or updates it. The password goes through a
  # pipe (<(...)), never through the command line, where other programs on the machine could read it.
  kubectl create secret generic "$db-db" --namespace "$namespace" --type kubernetes.io/basic-auth \
      --from-literal username="${db}_user" \
      --from-file password=<(printf '%s' "$password") \
      --dry-run=client --output yaml |
    kubectl label --local --filename - cnpg.io/reload=true --output yaml |
    kubectl apply --filename -
}

# A Secret with one key "password", in opspilot-apps
password_secret() {
  local name=$1 password=$2
  kubectl create secret generic "$name" --namespace opspilot-apps \
      --from-file password=<(printf '%s' "$password") \
      --dry-run=client --output yaml |
    kubectl apply --filename -
}

for namespace in opspilot-data opspilot-apps; do
  db_secret "$namespace" orders "$orders_password"
  db_secret "$namespace" inventory "$inventory_password"
  db_secret "$namespace" payments "$payments_password"
done

password_secret redis "$redis_password"                    # Redis; the gateway and inventory-service log in with it
password_secret gateway-demo-users "$demo_users_password"  # the demo users viewer / engineer / admin (local only)

# The gateway's JWT key pair (RSA, 2048 bit): private.pem signs tokens, public.pem checks them. Not from .env: it is
# made here once and then kept, because a new key would make every token issued so far invalid. The private key stays
# in memory (a variable and pipes), never in a file on this machine.
if kubectl get secret gateway-jwt --namespace opspilot-apps > /dev/null 2>&1; then
  echo "secret/gateway-jwt kept (it already exists)"
else
  private_key=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2> /dev/null)
  kubectl create secret generic gateway-jwt --namespace opspilot-apps \
    --from-file private.pem=<(printf '%s\n' "$private_key") \
    --from-file public.pem=<(printf '%s\n' "$private_key" | openssl pkey -pubout)
fi
