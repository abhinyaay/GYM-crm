#!/bin/sh
# One-off (idempotent) creation of the read-only analytics role on RDS.
# Run from a host inside the VPC (e.g. `aws ecs execute-command` into a core task, or a bastion) with
# the AWS CLI and psql available. Credentials are read from Secrets Manager at run time.
#   ENV=prod REGION=ap-south-1 RDS_HOST=<rds endpoint> ./create-analytics-role.sh
set -eu
: "${ENV:?}" "${REGION:?}" "${RDS_HOST:?}"
MASTER_SECRET_ARN=$(aws rds describe-db-instances --region "$REGION" --db-instance-identifier "gymops-$ENV-postgres" \
  --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)
MASTER_PW=$(aws secretsmanager get-secret-value --region "$REGION" --secret-id "$MASTER_SECRET_ARN" \
  --query SecretString --output text | python3 -c 'import sys,json;print(json.load(sys.stdin)["password"])')
ANALYTICS_PW=$(aws secretsmanager get-secret-value --region "$REGION" --secret-id "gymops-$ENV/app" \
  --query SecretString --output text | python3 -c 'import sys,json;print(json.load(sys.stdin)["ANALYTICS_DB_PASSWORD"])')
PGPASSWORD="$MASTER_PW" psql "host=$RDS_HOST dbname=gymops user=gymops sslmode=require" -v ON_ERROR_STOP=1 \
  -v pw="$ANALYTICS_PW" <<'SQL'
SELECT 'CREATE ROLE gym_analytics_ro LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gym_analytics_ro')\gexec
ALTER ROLE gym_analytics_ro WITH LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE gym_analytics_ro SET default_transaction_read_only = on;
ALTER ROLE gym_analytics_ro SET statement_timeout = '30s';
GRANT CONNECT ON DATABASE gymops TO gym_analytics_ro;
SQL
echo "gym_analytics_ro ready. Restart the core service once so Flyway's V3 grants apply (or re-run its GRANTs)."
