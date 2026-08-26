#!/usr/bin/env bash
# Execute Bruno collection against a live API (Testcontainers or remote).
# Usage: HEALTH_URL=http://127.0.0.1:8080/api/v1/health scripts/bruno-run.sh
# Set BRUNO_REQUIRED=1 to fail when bru CLI is missing or API is down (CI post-deploy).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/v1/health}"
REQUIRED="${BRUNO_REQUIRED:-0}"
fail_or_skip() {
  echo "$1"
  if [[ "$REQUIRED" == "1" ]]; then
    exit 1
  fi
  exit 0
}
if ! command -v bru >/dev/null 2>&1; then
  fail_or_skip "bruno CLI not installed — skip executed collection (file-presence still required)"
fi
if ! curl -fsS "$HEALTH_URL" >/dev/null; then
  fail_or_skip "API not reachable at $HEALTH_URL — skip executed Bruno"
fi
BASE_URL="${HEALTH_URL%/api/v1/health}"
cd "$ROOT/bruno"
bru run --env-var "baseUrl=${BASE_URL}" --sandbox developer
