#!/bin/sh
# Read-only post-deployment verification (safe against production): health, readiness,
# a public endpoint and the analytics service, with retries while tasks roll over.
set -eu
BASE_URL="${1:?usage: post_deploy_check.sh https://api.example.com}"
check() {
  name="$1"; url="$2"; expected="$3"
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" || echo 000)
    if [ "$code" = "$expected" ]; then echo "OK   $name ($code)"; return 0; fi
    echo "wait $name got $code (attempt $attempt)"; sleep 15
  done
  echo "FAIL $name"; return 1
}
check "web app"              "$BASE_URL/"                          200
check "core liveness"        "$BASE_URL/actuator/health/liveness"  200
check "core readiness"       "$BASE_URL/actuator/health/readiness" 200
check "plan catalogue"       "$BASE_URL/api/v1/plans"              200
check "auth required"        "$BASE_URL/api/v1/members"            401
check "analytics requires auth" "$BASE_URL/analytics/revenue"      401
check "metrics not public"   "$BASE_URL/actuator/prometheus"       404
