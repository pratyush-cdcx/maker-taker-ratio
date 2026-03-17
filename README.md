# User Maker/Taker Ratio Service

A high-performance Java service that consumes trade execution events from Aeron IPC and Kafka, classifies maker vs taker executions per user and per symbol, maintains running ratios across tumbling time windows (1H, 24H, 7D, 30D), and emits consolidated ratio updates back to both messaging systems.

Built with plain Java (no Spring Boot), SBE serialization, and ZGC for sub-millisecond GC pauses.

## Architecture

```
  Aeron IPC (stream 1001) ──> [Aeron Subscriber]──┐
                                                    │
                                               [Ratio Engine]──> [Aeron Publisher]──> Aeron IPC (stream 2001)
                                                    │
  Kafka (trade.executions) ──> [Kafka Consumer]──┘  └──> [Kafka Producer]──> Kafka (user.ratio.updates)
                                                    │
                                            [In-Memory Store]
                                                    │
                                          [REST API :8080]
```

## Quick Start

### Prerequisites

- Java 23+ (tested with Java 25)
- Apache Kafka (for Kafka path only; Aeron runs fully in-process)

### Build

```bash
./scripts/build.sh
```

### Run Service

```bash
# With both Aeron and Kafka enabled (requires Kafka running)
./scripts/run.sh

# With Aeron only (no Kafka needed)
KAFKA_ENABLED=false ./scripts/run.sh

# With Kafka only
AERON_ENABLED=false ./scripts/run.sh
```

### Run Benchmarks

```bash
# Aeron IPC benchmark (no Kafka required)
./scripts/run-benchmark.sh aeron 100000

# Kafka benchmark (requires Kafka at localhost:9092)
./scripts/start-kafka.sh  # in another terminal
./scripts/run-benchmark.sh kafka 100000

# Both benchmarks with comparison
./scripts/run-benchmark.sh both 100000
```

### Run Tests

```bash
# Run all tests
./gradlew test

# Run tests with coverage report
./gradlew test jacocoTestReport

# Verify 95% coverage threshold
./gradlew jacocoTestCoverageVerification
```

## REST API

```bash
# Get all window ratios for a user
curl http://localhost:8080/api/v1/users/12345/ratio

# Get specific window
curl http://localhost:8080/api/v1/users/12345/ratio?window=24H

# Get per-symbol ratio
curl http://localhost:8080/api/v1/users/12345/ratio?symbol=BTC-USDT

# Health check
curl http://localhost:8080/health
```

## Input Event (SBE-encoded TradeExecution)

| Field | Type | Description |
|-------|------|-------------|
| executionId | int64 | Unique execution ID |
| orderId | int64 | Order ID |
| userId | int64 | User ID |
| side | uint8 | BUY=0, SELL=1 |
| price | int64 | Price (minor units) |
| quantity | int64 | Quantity (minor units) |
| timestamp | int64 | Epoch ms |
| isMaker | uint8 | FALSE=0, TRUE=1 |
| symbol | varString | Trading pair (e.g., "BTC-USDT") |

## Output Event (SBE-encoded RatioUpdate)

Single message per trade containing all 4 window ratios:

| Field | Type | Description |
|-------|------|-------------|
| userId | int64 | User ID |
| timestamp | int64 | Update timestamp |
| makerCount1H / takerCount1H / ratio1H | int64/int64/double | 1-hour window |
| makerCount24H / takerCount24H / ratio24H | int64/int64/double | 24-hour window |
| makerCount7D / takerCount7D / ratio7D | int64/int64/double | 7-day window |
| makerCount30D / takerCount30D / ratio30D | int64/int64/double | 30-day window |
| symbol | varString | Trading pair (empty = overall) |

## Project Structure

```
src/main/java/com/coindcx/makertaker/
├── Application.java              # Main entry point
├── config/AppConfig.java         # Configuration loader
├── model/                        # Domain models
│   ├── TimeWindow.java           # Enum: 1H, 24H, 7D, 30D
│   ├── RatioKey.java             # Composite key (userId, symbol)
│   ├── TumblingBucket.java       # Time bucket for windowed aggregation
│   ├── WindowState.java          # State for one time window
│   └── UserSymbolState.java      # Full state per user-symbol pair
├── engine/RatioEngine.java       # Core processing logic
├── state/InMemoryRatioStore.java # ConcurrentHashMap-based store
├── aeron/                        # Aeron IPC integration
│   ├── AeronMediaDriverManager.java
│   ├── AeronTradeSubscriber.java
│   └── AeronRatioPublisher.java
├── kafka/                        # Kafka integration
│   ├── KafkaTradeConsumer.java
│   └── KafkaRatioProducer.java
├── rest/RatioRestServer.java     # REST API endpoint
└── benchmark/                    # Performance benchmarks
    ├── AeronBenchmark.java
    ├── KafkaBenchmark.java
    └── BenchmarkResult.java
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `AERON_ENABLED` | `true` | Enable Aeron IPC path |
| `KAFKA_ENABLED` | `true` | Enable Kafka path |
| `AERON_DIR` | auto-detected | Aeron media driver directory |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |
| `REST_PORT` | `8080` | REST API port |

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 23 |
| Build | Gradle 9.4 (Kotlin DSL) |
| Low-latency messaging | Aeron IPC |
| High-throughput messaging | Apache Kafka |
| Serialization | SBE (Simple Binary Encoding) |
| State store | In-memory ConcurrentHashMap |
| REST server | JDK HttpServer |
| GC | ZGC |
| Testing | JUnit 5 + Mockito (98.7% coverage) |
| Benchmarking | HDR Histogram |

## Documentation

- [Requirements & Specifications](docs/REQUIREMENTS_AND_SPECIFICATIONS.md)
- [Technical Reference Document (TRD)](docs/TRD.md)
