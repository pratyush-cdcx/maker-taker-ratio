#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="build/libs/maker-taker-ratio-service-1.0.0-all.jar"
if [ ! -f "$JAR" ]; then
    echo "JAR not found. Building first..."
    ./scripts/build.sh
fi

MODE="${1:-both}"
COUNT="${2:-100000}"
RPS="${3:-0}"

JVM_OPTS=(
    -XX:+UseZGC
    -Xms2g -Xmx2g
    -XX:+AlwaysPreTouch
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.base/java.nio=ALL-UNNAMED
    -cp "$JAR"
)

case "$MODE" in
    aeron)
        echo "Running Aeron IPC benchmark ($COUNT messages, burst + paced)..."
        java "${JVM_OPTS[@]}" com.coindcx.makertaker.benchmark.AeronBenchmark "$COUNT" "$RPS"
        ;;
    kafka)
        echo "Running Kafka benchmark ($COUNT messages, burst + paced)..."
        echo "  (Ensure Kafka is running at localhost:9092)"
        java "${JVM_OPTS[@]}" com.coindcx.makertaker.benchmark.KafkaBenchmark "$COUNT" "$RPS"
        ;;
    both)
        echo "Running both benchmarks ($COUNT messages each, burst + paced)..."
        echo ""

        echo "================================================================"
        echo "                    Aeron IPC Benchmark"
        echo "================================================================"
        java "${JVM_OPTS[@]}" com.coindcx.makertaker.benchmark.AeronBenchmark "$COUNT" "$RPS"
        echo ""

        echo "================================================================"
        echo "                    Kafka Benchmark"
        echo "================================================================"
        echo "  (Ensure Kafka is running at localhost:9092)"
        java "${JVM_OPTS[@]}" com.coindcx.makertaker.benchmark.KafkaBenchmark "$COUNT" "$RPS"
        echo ""
        ;;
    *)
        echo "Usage: $0 [aeron|kafka|both] [message_count] [target_rps]"
        echo ""
        echo "  message_count  Number of messages for burst mode (default: 100000)"
        echo "  target_rps     Target RPS for paced mode (default: 25000)"
        echo ""
        echo "Each mode runs burst AND paced benchmarks automatically."
        echo ""
        echo "Examples:"
        echo "  $0 aeron 100000          # Aeron: burst 100K + paced @25K RPS"
        echo "  $0 kafka 50000 10000     # Kafka: burst 50K + paced @10K RPS"
        echo "  $0 both                  # Both with defaults"
        exit 1
        ;;
esac
