#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Building Maker/Taker Ratio Service..."
./gradlew clean fatJar

echo ""
echo "Build complete. JAR: build/libs/maker-taker-ratio-service-1.0.0-all.jar"
