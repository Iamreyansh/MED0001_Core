#!/bin/sh
# Lambda Web Adapter startup script (handler = run.sh).
# Package places the Spring Boot fat jar next to this file as app.jar.
set -eu
cd "$(dirname "$0")"
exec java -jar app.jar
