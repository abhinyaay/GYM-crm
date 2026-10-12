#!/bin/sh
# Generates a local .env with strong random secrets (no credentials are ever committed).
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo ".env already exists - leaving it untouched"; exit 0
fi
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
sed -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(rand 32)|" \
    -e "s|^ANALYTICS_DB_PASSWORD=.*|ANALYTICS_DB_PASSWORD=$(rand 32)|" \
    -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(rand 32)|" \
    -e "s|^JWT_SECRET=.*|JWT_SECRET=$(rand 64)|" \
    -e "s|^BOOTSTRAP_ADMIN_PASSWORD=.*|BOOTSTRAP_ADMIN_PASSWORD=Adm1n$(rand 16)|" \
    -e "s|^DEMO_USER_PASSWORD=.*|DEMO_USER_PASSWORD=Dem0$(rand 12)|" \
    -e "s|^SIMULATED_GATEWAY_SECRET=.*|SIMULATED_GATEWAY_SECRET=$(rand 48)|" \
    -e "s|^ACCESS_MASTER_KEY=.*|ACCESS_MASTER_KEY=$(rand 48)|" \
    -e "s|^UNSUBSCRIBE_TOKEN_SECRET=.*|UNSUBSCRIBE_TOKEN_SECRET=$(rand 48)|" \
    .env.example > .env
chmod 600 .env
echo "Generated .env (bootstrap admin + demo passwords are inside it)"
