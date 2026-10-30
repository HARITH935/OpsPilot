#!/bin/bash
# Creates one database + one login per service (database-per-service).
# Postgres runs this automatically only on the FIRST start, when the data volume is empty.
set -euo pipefail

create_service_db() {
  local db="$1" user="$2" password="$3"
  # psql -v passes values as variables; :"x" quotes an identifier, :'x' quotes a string (safe against special characters)
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
CREATE USER :"usr" WITH PASSWORD :'pwd';
CREATE DATABASE :"db" OWNER :"usr";
-- By default every user may connect to every database; allow only the owning service
REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
SQL
  echo "created database '$db' owned by '$user'"
}

create_service_db orders    orders_user    "$ORDERS_DB_PASSWORD"
create_service_db inventory inventory_user "$INVENTORY_DB_PASSWORD"
create_service_db payments  payments_user  "$PAYMENTS_DB_PASSWORD"
