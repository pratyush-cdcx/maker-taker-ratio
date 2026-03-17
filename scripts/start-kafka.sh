#!/usr/bin/env bash
set -euo pipefail

echo "Starting local Kafka in KRaft mode (no Zookeeper)..."
echo ""

KAFKA_VERSION="${KAFKA_VERSION:-3.9.2}"
KAFKA_DIR="/tmp/kafka-$KAFKA_VERSION"
KAFKA_ARCHIVE="/tmp/kafka_2.13-$KAFKA_VERSION.tgz"

if [ ! -d "$KAFKA_DIR/bin" ]; then
    echo "Downloading Kafka $KAFKA_VERSION..."
    curl -fSL "https://downloads.apache.org/kafka/$KAFKA_VERSION/kafka_2.13-$KAFKA_VERSION.tgz" \
        -o "$KAFKA_ARCHIVE"
    tar -xzf "$KAFKA_ARCHIVE" -C /tmp/
    rm -rf "$KAFKA_DIR"
    mv "/tmp/kafka_2.13-$KAFKA_VERSION" "$KAFKA_DIR"
    rm -f "$KAFKA_ARCHIVE"
fi

LOG_DIR="/tmp/kraft-combined-logs-$$"
rm -rf "$LOG_DIR"

KRAFT_CONFIG="/tmp/kraft-server-$$.properties"
sed "s|^log.dirs=.*|log.dirs=$LOG_DIR|" "$KAFKA_DIR/config/kraft/server.properties" > "$KRAFT_CONFIG"

KAFKA_CLUSTER_ID="$($KAFKA_DIR/bin/kafka-storage.sh random-uuid)"

echo "Formatting storage with cluster ID: $KAFKA_CLUSTER_ID"
$KAFKA_DIR/bin/kafka-storage.sh format -t "$KAFKA_CLUSTER_ID" \
    -c "$KRAFT_CONFIG" 2>/dev/null || true

echo "Starting Kafka broker..."
$KAFKA_DIR/bin/kafka-server-start.sh "$KRAFT_CONFIG" &
KAFKA_PID=$!

echo "Waiting for Kafka to start..."
for i in $(seq 1 30); do
    if $KAFKA_DIR/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
        echo "Kafka is ready."
        break
    fi
    sleep 1
done

echo "Creating topics..."
$KAFKA_DIR/bin/kafka-topics.sh --create \
    --topic trade.executions \
    --partitions 1 \
    --replication-factor 1 \
    --bootstrap-server localhost:9092 2>/dev/null || true

$KAFKA_DIR/bin/kafka-topics.sh --create \
    --topic user.ratio.updates \
    --partitions 1 \
    --replication-factor 1 \
    --bootstrap-server localhost:9092 2>/dev/null || true

echo ""
echo "Kafka is running (PID: $KAFKA_PID)"
echo "  Bootstrap: localhost:9092"
echo "  Topics: trade.executions, user.ratio.updates"
echo "  Log dir: $LOG_DIR"
echo ""
echo "Press Ctrl+C to stop."

cleanup() {
    echo ""
    echo "Shutting down Kafka..."
    kill "$KAFKA_PID" 2>/dev/null || true
    wait "$KAFKA_PID" 2>/dev/null || true
    echo "Cleaning up temp files..."
    rm -rf "$LOG_DIR" "$KRAFT_CONFIG"
    echo "Done."
}

trap cleanup INT TERM EXIT
wait $KAFKA_PID 2>/dev/null
