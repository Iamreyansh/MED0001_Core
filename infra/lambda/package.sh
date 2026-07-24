#!/usr/bin/env bash
# Package Spring Boot fat jar + LWA run.sh into infra/lambda/{api|worker}.zip
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP="${1:-api}"
cd "$ROOT"
./gradlew ":apps:${APP}:bootJar" -x test
JAR="$(ls -1 "$ROOT/apps/${APP}/build/libs/med0001-${APP}.jar" | head -1)"
OUT="$ROOT/infra/lambda/build/${APP}"
ZIP="$ROOT/infra/lambda/${APP}.zip"
RUN_SH="$ROOT/infra/lambda/run.sh"

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$JAR" "$OUT/app.jar"
cp "$RUN_SH" "$OUT/run.sh"
chmod 755 "$OUT/run.sh"
rm -f "$ZIP"
# -X stores unix permissions so Lambda can execute run.sh
(cd "$OUT" && zip -Xqr "$ZIP" run.sh app.jar)
echo "Wrote $ZIP ($(unzip -l "$ZIP" | awk 'END{print $1,$2,$3,$4}'))"
unzip -l "$ZIP"
