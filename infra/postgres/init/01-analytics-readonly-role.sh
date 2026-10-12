#!/bin/sh
# Runs once on first database initialisation (docker-entrypoint-initdb.d).
# Creates the least-privilege role used by the analytics service; the Flyway migration
# V3__analytics_read_access.sql grants it SELECT on the business tables.
set -eu
: "${ANALYTICS_DB_PASSWORD:?ANALYTICS_DB_PASSWORD must be set}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v pw="$ANALYTICS_DB_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
CREATE ROLE gym_analytics_ro LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE gym_analytics_ro SET default_transaction_read_only = on;
ALTER ROLE gym_analytics_ro SET statement_timeout = '30s';
GRANT CONNECT ON DATABASE :"db" TO gym_analytics_ro;
SQL
