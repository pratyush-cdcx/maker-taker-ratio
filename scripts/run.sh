#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="build/libs/maker-taker-ratio-service-1.0.0-all.jar"
if [ ! -f "$JAR" ]; then
    echo "JAR not found. Building first..."
    ./scripts/build.sh
fi

AERON_DIR="${AERON_DIR:-$([ -d /dev/shm ] && echo /dev/shm/aeron-ratio || echo ${TMPDIR:-/tmp}/aeron-ratio)}"
echo "Starting Maker/Taker Ratio Service..."
echo "  REST API: http://localhost:${REST_PORT:-8080}/api/v1/users/{userId}/ratio"
echo "  Aeron IPC: $AERON_DIR (streams 1001/2001)"
echo "  Kafka: localhost:9092 (topics: trade.executions / user.ratio.updates)"
echo ""
echo "Press Ctrl+C to stop."
echo ""

exec java \
    -XX:+UseZGC \
    -Xms2g -Xmx2g \
    -XX:+AlwaysPreTouch \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    -jar "$JAR" "$@"
