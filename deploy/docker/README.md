# deploy/docker

Local development stack (Docker Compose). Grows through Phases 1–3; today it runs **PostgreSQL** only.

## First-time setup
```bash
cd deploy/docker
cp .env.example .env          # then replace every change-me, e.g. with: openssl rand -hex 16
```
`.env` holds real passwords and is gitignored — never commit it.

## Start / stop (from the repo root)
```bash
docker compose -f deploy/docker/docker-compose.yml up -d      # start
docker compose -f deploy/docker/docker-compose.yml ps         # status (wait for "healthy")
docker compose -f deploy/docker/docker-compose.yml down       # stop, keep data
docker compose -f deploy/docker/docker-compose.yml down -v    # stop AND delete all data
```

## PostgreSQL
One container, **one database + one login per service** (database-per-service):

| Database | User | Password env var | Used by |
|---|---|---|---|
| `orders` | `orders_user` | `ORDERS_DB_PASSWORD` | order-service |
| `inventory` | `inventory_user` | `INVENTORY_DB_PASSWORD` | inventory-service |
| `payments` | `payments_user` | `PAYMENTS_DB_PASSWORD` | payment-service |

Each user can connect **only** to its own database. The databases are created by
`postgres/init/01-create-service-databases.sh`, which Postgres runs **only on first start** (empty volume).
If you change passwords in `.env` later, run `down -v` and `up -d` again to re-create them.

Open a SQL shell: `docker exec -it opspilot-postgres psql -U postgres`
