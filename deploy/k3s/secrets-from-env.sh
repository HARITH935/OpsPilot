#!/usr/bin/env bash
# Creates the Kubernetes Secrets of the local kind cluster from deploy/docker/.env: the same passwords as the Compose
# stack. Nothing secret goes into Git, onto a command line or onto the screen. The cloud cluster gets Sealed Secrets
# instead (Phase 5, ADR 0010). Run after kind-up.sh; running it again updates the Secrets.
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

# One login per service database, as a Secret "<database>-db" next to the database in opspilot-data (CloudNativePG
# reads it only from its own namespace). Type basic-auth = the two keys "username" and "password", which CloudNativePG
# expects. The label cnpg.io/reload makes it apply a changed password at once.
db_secret() {
  local db=$1 password
  password=$(env_value "$2")
  # create --dry-run only writes the Secret as YAML; apply then creates or updates it. The password goes through a
  # pipe (<(...)), never through the command line, where other programs on the machine could read it.
  kubectl create secret generic "$db-db" --namespace opspilot-data --type kubernetes.io/basic-auth \
      --from-literal username="${db}_user" \
      --from-file password=<(printf '%s' "$password") \
      --dry-run=client --output yaml |
    kubectl label --local --filename - cnpg.io/reload=true --output yaml |
    kubectl apply --filename -
}

db_secret orders ORDERS_DB_PASSWORD
db_secret inventory INVENTORY_DB_PASSWORD
db_secret payments PAYMENTS_DB_PASSWORD
