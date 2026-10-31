#!/usr/bin/env bash
# Foreground synthetic runtime; use a persistent terminal/session. No container mutation here.
set -euo pipefail
: "${OBS135_JAR:?Set exact immutable jar path}"
: "${JAVA_HOME:?Set JDK21 path}"
: "${DATABASE_URL:?Set dedicated PostgreSQL URL}"
: "${DATABASE_USERNAME:?Set synthetic DB user}"
: "${DATABASE_PASSWORD:?Set synthetic DB password}"
: "${APP_STORAGE_ROOT:?Set dedicated uploads root}"
exec "$JAVA_HOME/bin/java" -jar "$OBS135_JAR" --server.port=19835
