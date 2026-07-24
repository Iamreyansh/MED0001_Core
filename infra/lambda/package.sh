#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP="${1:-api}"
cd "$ROOT"
./gradlew ":apps:${APP}:bootJar" -x test
JAR="$(ls -1 "$ROOT/apps/${APP}/build/libs/med0001-${APP}.jar" | head -1)"
OUT="$ROOT/infra/lambda/build/${APP}"
rm -rf "$OUT"
mkdir -p "$OUT"
# Lambda Web Adapter + Java: fat jar at root as function artifact
cp "$JAR" "$OUT/${APP}.jar"
(cd "$OUT" && zip -qr "../${APP}.zip" "${APP}.jar")
echo "Wrote $ROOT/infra/lambda/${APP}.zip"
