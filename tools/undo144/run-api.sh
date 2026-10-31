#!/bin/bash
set -euo pipefail
: "${1:?Usage: run-api.sh /absolute/path/to/immutable.jar}"
export JAVA_HOME=/tmp/ultistats-backend-jdk21
export DATABASE_URL=jdbc:postgresql://127.0.0.1:19444/undo144
export DATABASE_USERNAME=undo144
export DATABASE_PASSWORD=undo144-synthetic
export APP_STORAGE_ROOT=/tmp/ultistats-undo144/uploads
exec "$JAVA_HOME/bin/java" -jar "$1" --server.address=127.0.0.1 --server.port=19844
