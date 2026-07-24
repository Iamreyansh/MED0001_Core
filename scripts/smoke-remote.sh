#!/usr/bin/env bash
# Poll remote health until HTTP 200 and envelope reports UP.
# Usage: smoke-remote.sh <HEALTH_URL>
# Env: ATTEMPTS (default 12), SLEEP_SECS (default 15)
set -euo pipefail

HEALTH_URL="${1:-${HEALTH_URL:-}}"
if [ -z "$HEALTH_URL" ]; then
  echo "HEALTH_URL required" >&2
  exit 1
fi

ATTEMPTS="${ATTEMPTS:-12}"
SLEEP_SECS="${SLEEP_SECS:-15}"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

echo "Smoke: ${HEALTH_URL} (attempts=${ATTEMPTS}, sleep=${SLEEP_SECS}s)"
for i in $(seq 1 "$ATTEMPTS"); do
  code="$(curl -sS --connect-timeout 5 -o "$BODY_FILE" -w "%{http_code}" "$HEALTH_URL" 2>/dev/null || true)"
  [ -n "$code" ] || code="000"
  body="$(cat "$BODY_FILE" 2>/dev/null || true)"
  if [ "$code" = "200" ] \
    && grep -q '"success"[[:space:]]*:[[:space:]]*true' <<<"$body" \
    && grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$body"; then
    echo "$body"
    echo "Smoke OK (attempt ${i}/${ATTEMPTS})"
    exit 0
  fi
  echo "Attempt ${i}/${ATTEMPTS}: HTTP ${code}"
  if [ -n "$body" ]; then
    echo "$body"
  fi
  sleep "$SLEEP_SECS"
done

echo "Remote health check failed: ${HEALTH_URL}" >&2
cat "$BODY_FILE" 2>/dev/null || true
exit 1
