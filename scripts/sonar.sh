#!/usr/bin/env bash
#
# Reproducible SonarQube scan.
#
# Starts the community server from docker-compose, waits for it, bootstraps an
# analysis token, and scans this project with the JaCoCo report wired in as
# coverage evidence. Safe to run repeatedly: the token is revoked and reissued,
# and the first-run admin password change is a no-op afterwards.
#
#   ./scripts/sonar.sh            scan against a local server
#   SONAR_URL=... ./scripts/sonar.sh   scan against an existing server
#
set -euo pipefail

cd "$(dirname "$0")/.."

SONAR_PORT="${SONAR_PORT:-9000}"
export SONAR_PORT
SONAR_URL="${SONAR_URL:-http://localhost:$SONAR_PORT}"
# SonarQube 26 enforces a password policy (upper, lower, digit, symbol), so this
# local-only default has to satisfy it.
SONAR_ADMIN_PASSWORD="${SONAR_ADMIN_PASSWORD:-EbmsLocalDev1!}"
SONAR_TOKEN_NAME="${SONAR_TOKEN_NAME:-ebms-integrate-local}"
SONAR_WAIT_SECONDS="${SONAR_WAIT_SECONDS:-600}"

if [ -z "${SONAR_TOKEN:-}" ] && [ "$SONAR_URL" = "http://localhost:$SONAR_PORT" ]; then
    echo "==> Starting SonarQube (docker compose --profile sonar)"
    docker compose --profile sonar up -d sonarqube
fi

echo "==> Waiting for $SONAR_URL to report UP (up to ${SONAR_WAIT_SECONDS}s)"
deadline=$(( $(date +%s) + SONAR_WAIT_SECONDS ))
until curl -fsS "$SONAR_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "SonarQube did not become healthy within ${SONAR_WAIT_SECONDS}s." >&2
        echo "Logs: docker compose --profile sonar logs sonarqube" >&2
        exit 1
    fi
    sleep 5
done
echo "    up."

if [ -z "${SONAR_TOKEN:-}" ]; then
    # A fresh server starts with admin/admin. Change it once; on later runs the
    # password already works and this block is skipped.
    if ! curl -sS -u "admin:$SONAR_ADMIN_PASSWORD" \
            "$SONAR_URL/api/authentication/validate" 2>/dev/null | grep -q '"valid":true'; then
        echo "==> First run: setting the admin password"
        response=$(curl -sS -u "admin:admin" -X POST \
            --data-urlencode "login=admin" \
            --data-urlencode "previousPassword=admin" \
            --data-urlencode "password=$SONAR_ADMIN_PASSWORD" \
            -w '\n%{http_code}' \
            "$SONAR_URL/api/users/change_password")
        status=$(printf '%s' "$response" | tail -n1)
        if [ "$status" != "200" ] && [ "$status" != "204" ]; then
            echo "Could not set the admin password (HTTP $status):" >&2
            printf '%s\n' "$response" | sed '$d' >&2
            echo "Set SONAR_ADMIN_PASSWORD to a value that satisfies the server's policy." >&2
            exit 1
        fi
    fi

    echo "==> Issuing analysis token '$SONAR_TOKEN_NAME'"
    curl -fsS -u "admin:$SONAR_ADMIN_PASSWORD" -X POST \
        --data-urlencode "name=$SONAR_TOKEN_NAME" \
        "$SONAR_URL/api/user_tokens/revoke" >/dev/null

    SONAR_TOKEN=$(curl -fsS -u "admin:$SONAR_ADMIN_PASSWORD" -X POST \
        --data-urlencode "name=$SONAR_TOKEN_NAME" \
        "$SONAR_URL/api/user_tokens/generate" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

    if [ -z "$SONAR_TOKEN" ]; then
        echo "Could not obtain an analysis token from $SONAR_URL." >&2
        exit 1
    fi
fi

echo "==> Building with coverage and scanning"
mvn -B verify sonar:sonar \
    -Dsonar.host.url="$SONAR_URL" \
    -Dsonar.token="$SONAR_TOKEN"

echo
echo "Dashboard: $SONAR_URL/dashboard?id=ebms-msh"
