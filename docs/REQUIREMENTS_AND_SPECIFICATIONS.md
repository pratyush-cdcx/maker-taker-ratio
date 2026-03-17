# User Maker/Taker Ratio Service — Requirements & Specifications

## Document Information

| Field | Value |
|-------|-------|
| **Service Name** | User Maker/Taker Ratio Service |
| **Version** | 2.0.0 |
| **Status** | Approved |
| **Author** | Pratyush Dev |
| **Created** | February 23, 2026 |
| **Last Updated** | March 17, 2026 |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Problem Statement](#2-problem-statement)
3. [Goals & Non-Goals](#3-goals--non-goals)
4. [Architecture](#4-architecture)
5. [Data Flow](#5-data-flow)
6. [API Specifications](#6-api-specifications)
7. [Data Models](#7-data-models)
8. [Configuration](#8-configuration)
9. [Dependencies](#9-dependencies)
10. [Performance Requirements](#10-performance-requirements)
11. [Testing Strategy](#11-testing-strategy)
12. [Benchmark Requirements](#12-benchmark-requirements)
13. [Confirmed Decisions](#13-confirmed-decisions)

---

## 1. Overview

### 1.1 Purpose

The User Maker/Taker Ratio Service consumes trade execution events from two independent messaging paths — Aeron IPC (low-latency) and Kafka (high-throughput) — classifies each execution as maker or taker, maintains running ratios per user and per user-symbol pair across tumbling time windows, and publishes a consolidated ratio update (all windows in a single message) back to Aeron and Kafka on every trade.

### 1.2 Background

In trading systems, understanding the maker/taker ratio is crucial for:
- **Fee Calculation**: Exchanges typically offer fee rebates for makers (liquidity providers) while charging higher fees for takers (liquidity consumers)
- **User Behavior Analysis**: Understanding trading patterns helps in product decisions
- **Risk Management**: High taker ratios may indicate aggressive trading behavior
- **Tiered Pricing**: Users with favorable ratios may qualify for better fee tiers

### 1.3 Definitions

| Term | Definition |
|------|------------|
| **Maker** | A limit order that sits on the order book and adds liquidity — does not immediately match |
| **Taker** | An order that fills against an existing order on the book and removes liquidity |
| **Maker Ratio** | `maker_count / (maker_count + taker_count)` — proportion of trades where user was maker |
| **isMaker** | Boolean field in execution event indicating maker (`true`) or taker (`false`) status |
| **Aeron IPC** | Aeron Inter-Process Communication — shared-memory transport for ultra-low-latency messaging between processes on the same machine |
| **Kafka** | Distributed event streaming platform for high-throughput data pipelines |
| **Tumbling Window** | A fixed-size, non-overlapping time window for aggregation (e.g., each 1-hour bucket is independent) |
| **SBE** | Simple Binary Encoding — zero-copy, schema-based binary serialization |
| **Time Window** | Configurable period for ratio calculation: 1 hour, 24 hours, 7 days, or 30 days |

---

## 2. Problem Statement

### 2.1 Current State

Currently, there is no centralized service to:
- Track and classify trade executions as maker or taker
- Calculate real-time maker/taker ratios per user and per user-symbol pair
- Provide this data to downstream services for fee calculation and analytics

### 2.2 Desired State

A dedicated, low-latency service that:
- Consumes trade execution events from Aeron IPC and Kafka as **independent, parallel paths** (no deduplication)
- Classifies each execution as maker or taker based on the `isMaker` field
- Maintains running ratios per user (overall) and per user-symbol pair across tumbling time windows (1h, 24h, 7d, 30d)
- Emits a **single consolidated ratio update message** (containing all 4 windows) on every trade via Aeron and Kafka
- Provides a basic REST API for querying current ratios
- Achieves P99 < 10ms end-to-end latency and supports 25K avg / 100K peak RPS

---

## 3. Goals & Non-Goals

### 3.1 Goals

| ID | Goal | Priority |
|----|------|----------|
| G1 | Consume trade execution events from Aeron IPC | P0 |
| G2 | Consume trade execution events from Kafka topics | P0 |
| G3 | Classify executions as maker or taker using the `isMaker` field | P0 |
| G4 | Calculate and maintain maker/taker ratios (count-based) per user | P0 |
| G5 | Calculate and maintain maker/taker ratios per user-symbol pair | P0 |
| G6 | Emit consolidated ratio updates (all 4 windows) to Aeron IPC on every trade | P0 |
| G7 | Emit consolidated ratio updates (all 4 windows) to Kafka on every trade | P0 |
| G8 | Support tumbling time windows: 1h, 24h, 7d, 30d | P0 |
| G9 | Provide REST API for ratio queries (`GET /api/v1/users/{userId}/ratio`) | P0 |
| G10 | Runnable benchmark demo comparing Aeron vs Kafka latency and throughput | P0 |
| G11 | Achieve 95% unit test coverage | P0 |

### 3.2 Non-Goals

| ID | Non-Goal | Reason |
|----|----------|--------|
| NG1 | Fee calculation | Handled by downstream fee service |
| NG2 | Order matching | Handled by matching engine |
| NG3 | Trade settlement | Handled by settlement service |
| NG4 | Historical trade storage | Handled by trade history service |
| NG5 | Volume-based ratio calculation | Only count-based ratios are required |
| NG6 | Deduplication across Aeron and Kafka | Paths are independent; no cross-path dedup |
| NG7 | State persistence (Redis/RocksDB) | In-memory only for this version |
| NG8 | Replay/recovery from persistent store | Out of scope; state is ephemeral |
| NG9 | Kubernetes/production deployment | Demo project; shell scripts only |
| NG10 | Docker containerization | Not required; native execution via shell scripts |

### 3.3 Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Framework | Plain Java + manual DI | No Spring Boot overhead; lightest possible for low-latency hot path |
| Java Version | Java 23 | Latest features, best performance |
| Build Tool | Gradle (Kotlin DSL) | Modern, fast builds, good SBE plugin support |
| Aeron Transport | IPC (shared memory) | Fastest transport for same-machine demo; eliminates network overhead |
| Deduplication | None | Aeron and Kafka are independent parallel paths |
| Serialization | SBE for both Aeron and Kafka | Uniform zero-copy encoding; maximum performance |
| Time Windows | Tumbling (fixed buckets) | Simpler than sliding; sufficient for ratio accuracy |
| Ratio Output | Single message with all 4 windows | Fewer messages, atomic update for all windows |
| Per-Symbol Ratios | Yes | Per-user-per-symbol AND overall user ratios |
| State Storage | In-memory `ConcurrentHashMap` | No external dependencies; fastest access |
| GC Strategy | ZGC | Sub-millisecond pauses for low-latency |
| REST API | Included (P0) | Basic query endpoint using `com.sun.net.httpserver` |

---

## 4. Architecture

### 4.1 High-Level Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │      User Maker/Taker Ratio Service         │
                        ├─────────────────────────────────────────────┤
                        │                                             │
  Aeron IPC             │  ┌───────────┐    ┌────────────────────┐   │            Aeron IPC
  (Stream 1001)  ──────▶│  │  Aeron    │───▶│                    │───▶── (Stream 2001)
                        │  │Subscriber │    │                    │   │
                        │  └───────────┘    │    Ratio Engine    │   │
                        │                   │                    │   │            Kafka
  Kafka                 │  ┌───────────┐    │  - Classify        │   │     (user.ratio.updates)
  (trade.executions)───▶│  │  Kafka    │───▶│  - Aggregate       │───▶──
                        │  │ Consumer  │    │  - Window Mgmt     │   │
                        │  └───────────┘    │                    │   │
                        │                   └─────────┬──────────┘   │
                        │                             │              │
                        │                   ┌─────────▼──────────┐   │
                        │                   │  In-Memory Store   │   │
                        │                   │  (ConcurrentMap)   │   │
                        │                   └────────────────────┘   │
                        │                                             │
                        │                   ┌────────────────────┐   │
                        │                   │   REST API         │   │
                        │                   │   (:8080)          │   │
                        │                   └────────────────────┘   │
                        └─────────────────────────────────────────────┘
```

### 4.2 Component Description

| Component | Responsibility |
|-----------|----------------|
| **Aeron Subscriber** | Poll trade execution events from Aeron IPC stream 1001 using `BusySpinIdleStrategy` |
| **Kafka Consumer** | Poll trade execution events from `trade.executions` topic with SBE deserialization |
| **Ratio Engine** | Stateless processor: classifies maker/taker, updates state store, computes ratios for all windows |
| **In-Memory Store** | `ConcurrentHashMap<CompositeKey(userId, symbol), TumblingWindowState>` for per-user-symbol; separate map for overall user ratios |
| **Aeron Publisher** | Publish consolidated ratio updates to Aeron IPC stream 2001 via `ExclusivePublication` |
| **Kafka Producer** | Publish consolidated ratio updates to `user.ratio.updates` topic with SBE serialization |
| **REST API** | Lightweight HTTP server (`com.sun.net.httpserver`) exposing `GET /api/v1/users/{userId}/ratio` |

### 4.3 Technology Stack

| Layer | Technology | Justification |
|-------|------------|---------------|
| **Language** | Java 23 | Latest LTS-adjacent; best Aeron support; modern features |
| **Build Tool** | Gradle (Kotlin DSL) | Fast builds, SBE code generation, JaCoCo integration |
| **Messaging (Low Latency)** | Aeron IPC | Sub-microsecond latency; shared-memory transport |
| **Messaging (High Throughput)** | Apache Kafka | Durability, replay capability, ecosystem |
| **State Store** | In-memory `ConcurrentHashMap` | Zero external dependencies; fastest access |
| **Serialization** | SBE (both Aeron and Kafka) | Zero-copy, schema-based, uniform across both paths |
| **REST Server** | `com.sun.net.httpserver` | JDK built-in; zero dependencies; sufficient for query endpoint |
| **Testing** | JUnit 5 + Mockito | Industry standard; 95% coverage target |
| **Benchmarking** | HDR Histogram | Accurate latency percentile capture |
| **GC** | ZGC | Sub-millisecond pause times |

---

## 5. Data Flow

### 5.1 Processing Flow (Both Paths Identical, Independent)

```
1. Receive Trade Execution Event (SBE-encoded)
         │
         ▼
2. Decode via SBE (zero-copy)
         │
         ▼
3. Classify: isMaker=true → MAKER, isMaker=false → TAKER
         │
         ▼
4. Update State Store
   ├── Per-user overall: increment maker_count or taker_count in tumbling window buckets
   └── Per-user-symbol: increment maker_count or taker_count in tumbling window buckets
         │
         ▼
5. Compute Ratios (all 4 windows: 1H, 24H, 7D, 30D)
   └── ratio = maker_count / (maker_count + taker_count) per window
         │
         ▼
6. Build Consolidated Ratio Update (single message, all 4 windows)
         │
         ▼
7. Publish
   ├── Aeron IPC (stream 2001) — SBE encoded
   └── Kafka (user.ratio.updates) — SBE encoded
```

### 5.2 Threading Model

```
Thread 1: Aeron Poll Loop
  └── BusySpinIdleStrategy → poll Subscription → decode SBE → RatioEngine.process()
      → publish to Aeron Publisher + Kafka Producer

Thread 2: Kafka Poll Loop
  └── KafkaConsumer.poll() → decode SBE → RatioEngine.process()
      → publish to Aeron Publisher + Kafka Producer

Thread 3: REST API (HttpServer thread pool)
  └── Handle GET requests → read from In-Memory Store → return JSON

State Store: ConcurrentHashMap (thread-safe, shared across Thread 1 & 2)
```

---

## 6. API Specifications

### 6.1 Input: Trade Execution Event

**Aeron IPC Configuration:**
- Channel: `aeron:ipc`
- Stream ID: `1001`

**Kafka Topic Configuration:**
- Topic: `trade.executions`
- Partitions: 1 (for demo; configurable)

**SBE Message Schema:**

```xml
<sbe:message name="TradeExecution" id="1">
    <field name="executionId" id="1" type="int64"/>
    <field name="orderId" id="2" type="int64"/>
    <field name="userId" id="3" type="int64"/>
    <field name="side" id="4" type="Side"/>
    <field name="price" id="5" type="int64"/>
    <field name="quantity" id="6" type="int64"/>
    <field name="timestamp" id="7" type="int64"/>
    <field name="isMaker" id="8" type="BooleanType"/>
    <data name="symbol" id="9" type="varStringEncoding"/>
</sbe:message>

<enum name="Side" encodingType="uint8">
    <validValue name="BUY">0</validValue>
    <validValue name="SELL">1</validValue>
</enum>

<enum name="BooleanType" encodingType="uint8">
    <validValue name="FALSE">0</validValue>
    <validValue name="TRUE">1</validValue>
</enum>
```

### 6.2 Output: Consolidated Ratio Update Event

A single message per trade containing ratios for **all 4 time windows**.

**Aeron IPC Configuration:**
- Channel: `aeron:ipc`
- Stream ID: `2001`

**Kafka Topic Configuration:**
- Topic: `user.ratio.updates`
- Partitions: 1 (for demo; configurable)

**SBE Message Schema:**

```xml
<sbe:message name="RatioUpdate" id="2">
    <field name="userId" id="1" type="int64"/>
    <field name="timestamp" id="2" type="int64"/>
    <field name="makerCount1H" id="3" type="int64"/>
    <field name="takerCount1H" id="4" type="int64"/>
    <field name="ratio1H" id="5" type="double"/>
    <field name="makerCount24H" id="6" type="int64"/>
    <field name="takerCount24H" id="7" type="int64"/>
    <field name="ratio24H" id="8" type="double"/>
    <field name="makerCount7D" id="9" type="int64"/>
    <field name="takerCount7D" id="10" type="int64"/>
    <field name="ratio7D" id="11" type="double"/>
    <field name="makerCount30D" id="12" type="int64"/>
    <field name="takerCount30D" id="13" type="int64"/>
    <field name="ratio30D" id="14" type="double"/>
    <data name="symbol" id="15" type="varStringEncoding"/>
</sbe:message>
```

### 6.3 REST API

#### Get User Ratio

```
GET /api/v1/users/{userId}/ratio?window={window}&symbol={symbol}
```

**Parameters:**
- `userId` (path, required): User ID (long)
- `window` (query, optional): One of `1H`, `24H`, `7D`, `30D` (default: all windows)
- `symbol` (query, optional): Trading pair (e.g., `BTC-USDT`). If omitted, returns overall user ratio.

**Response (all windows):**
```json
{
  "userId": 12345,
  "symbol": null,
  "windows": {
    "1H":  { "makerCount": 30,  "takerCount": 10,  "ratio": 0.75 },
    "24H": { "makerCount": 150, "takerCount": 50,  "ratio": 0.75 },
    "7D":  { "makerCount": 800, "takerCount": 200, "ratio": 0.80 },
    "30D": { "makerCount": 3000,"takerCount": 1000,"ratio": 0.75 }
  },
  "lastUpdated": 1708700000000
}
```

**Response (single window):**
```json
{
  "userId": 12345,
  "symbol": "BTC-USDT",
  "window": "24H",
  "makerCount": 150,
  "takerCount": 50,
  "ratio": 0.75,
  "lastUpdated": 1708700000000
}
```

---

## 7. Data Models

### 7.1 Internal Data Structures

```java
// Composite key for per-user-symbol state
record RatioKey(long userId, String symbol) {}

// Per-window tumbling bucket state
class TumblingBucket {
    long bucketStartEpochMs;
    long makerCount;
    long takerCount;
}

// State for a single time window
class WindowState {
    TimeWindow window;
    TumblingBucket[] buckets;  // circular buffer of buckets
    int currentBucketIndex;
    long totalMakerCount;      // aggregate across active buckets
    long totalTakerCount;
}

// Full state for a user-symbol pair
class UserSymbolState {
    RatioKey key;
    WindowState[] windows;     // one per TimeWindow (4 total)
    long lastUpdatedTimestamp;

    double getRatio(TimeWindow w) {
        WindowState ws = windows[w.ordinal()];
        long total = ws.totalMakerCount + ws.totalTakerCount;
        return total == 0 ? 0.0 : (double) ws.totalMakerCount / total;
    }
}

enum TimeWindow {
    HOUR_1(Duration.ofHours(1), Duration.ofMinutes(1)),    // 60 x 1-min buckets
    HOUR_24(Duration.ofHours(24), Duration.ofMinutes(5)),  // 288 x 5-min buckets
    DAY_7(Duration.ofDays(7), Duration.ofHours(1)),        // 168 x 1-hour buckets
    DAY_30(Duration.ofDays(30), Duration.ofHours(1));      // 720 x 1-hour buckets

    final Duration windowDuration;
    final Duration bucketDuration;
}

// Primary state stores
Map<RatioKey, UserSymbolState> perSymbolStore;   // per-user-per-symbol
Map<Long, UserSymbolState> overallUserStore;      // per-user overall (symbol=null)
```

---

## 8. Configuration

### 8.1 Application Configuration

Configuration is via Java system properties or a simple properties file:

```properties
# Aeron
aeron.ipc.input.stream.id=1001
aeron.ipc.output.stream.id=2001
aeron.dir=/dev/shm/aeron-ratio

# Kafka
kafka.bootstrap.servers=localhost:9092
kafka.input.topic=trade.executions
kafka.output.topic=user.ratio.updates
kafka.group.id=maker-taker-ratio-service
kafka.auto.offset.reset=latest

# Ratio
ratio.windows=1H,24H,7D,30D

# REST
rest.port=8080

# Logging
log.level=INFO
```

### 8.2 Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AERON_DIR` | Aeron media driver directory | `/dev/shm/aeron-ratio` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `REST_PORT` | REST API port | `8080` |
| `LOG_LEVEL` | Application log level | `INFO` |

---

## 9. Dependencies

### 9.1 External Services

| Service | Purpose | Required for Demo |
|---------|---------|-------------------|
| Kafka Broker | Event ingestion and publishing via Kafka path | Yes (for Kafka benchmark) |
| Aeron Media Driver | IPC messaging (embedded in-process) | No (embedded) |

### 9.2 Library Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // Aeron
    implementation("io.aeron:aeron-all:1.46.7")
    implementation("org.agrona:agrona:1.23.1")

    // Kafka
    implementation("org.apache.kafka:kafka-clients:3.9.0")

    // SBE
    implementation("uk.co.real-logic:sbe-all:1.33.1")

    // Benchmarking
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    // JSON (for REST API responses only)
    implementation("com.google.code.gson:gson:2.11.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}
```

---

## 10. Performance Requirements

### 10.1 Latency Requirements

| Metric | Target | Maximum |
|--------|--------|---------|
| End-to-End Latency (P50) | < 1 ms | 5 ms |
| End-to-End Latency (P99) | < 10 ms | 10 ms |
| Aeron path processing | < 100 μs | 1 ms |
| Kafka path processing | < 5 ms | 10 ms |

### 10.2 Throughput Requirements

| Metric | Average | Peak |
|--------|---------|------|
| Requests per Second | 25,000 | 100,000 |
| Ratio Updates per Second | 25,000 | 100,000 |

### 10.3 Resource Requirements (Development)

| Resource | Specification |
|----------|--------------|
| CPU | 2+ cores |
| Memory | 4 GB heap |
| Disk | Minimal (in-memory state) |
| Shared Memory | `/dev/shm` for Aeron IPC |

---

## 11. Testing Strategy

### 11.1 Unit Tests (Target: 95% coverage)

| Area | Coverage Target | Framework |
|------|-----------------|-----------|
| Ratio Engine | 95% | JUnit 5 + Mockito |
| Tumbling Window Manager | 95% | JUnit 5 |
| In-Memory State Store | 95% | JUnit 5 |
| SBE Codec encode/decode | 95% | JUnit 5 |
| Aeron Subscriber/Publisher | 95% | JUnit 5 + EmbeddedMediaDriver |
| Kafka Consumer/Producer | 95% | JUnit 5 + Mockito |
| REST API handler | 95% | JUnit 5 |

### 11.2 Integration Tests

| Test | Description |
|------|-------------|
| Aeron End-to-End | Publish trade via Aeron IPC → verify ratio update received |
| Kafka End-to-End | Produce trade to Kafka → verify ratio update produced |
| REST Query | Process trades → query REST API → verify ratios correct |

### 11.3 Coverage Enforcement

- JaCoCo configured with 95% line coverage minimum
- Build fails if coverage drops below threshold

---

## 12. Benchmark Requirements

### 12.1 Benchmark Scenarios

| Scenario | Description |
|----------|-------------|
| Aeron Latency | Publish N trades via Aeron IPC, measure end-to-end latency percentiles (p50/p99/p999) |
| Kafka Latency | Publish N trades via Kafka, measure end-to-end latency percentiles (p50/p99/p999) |
| Aeron Throughput | Sustain maximum events/sec via Aeron IPC for 30 seconds |
| Kafka Throughput | Sustain maximum events/sec via Kafka for 30 seconds |

### 12.2 Benchmark Output

Each benchmark run produces:
- Latency histogram (p50, p90, p99, p999, max)
- Throughput (events/sec sustained)
- Side-by-side comparison table

### 12.3 Benchmark Execution

```bash
./scripts/build.sh                    # Build the project
./scripts/start-kafka.sh              # Start local Kafka (for Kafka benchmark)
./scripts/run-benchmark.sh aeron      # Run Aeron benchmark
./scripts/run-benchmark.sh kafka      # Run Kafka benchmark
./scripts/run-benchmark.sh both       # Run both and compare
```

---

## 13. Confirmed Decisions

| # | Decision | Resolution |
|---|----------|-----------|
| 1 | Per-symbol ratios | **Yes**: Per-user-per-symbol AND overall user ratios |
| 2 | Ratio update emission | **Every trade**: single message with all 4 windows |
| 3 | Historical queries beyond 30d | **No**: only 1h/24h/7d/30d tumbling windows |
| 4 | REST API in v1 | **Yes**: basic GET endpoint included as P0 |
| 5 | Framework | **Plain Java**: no Spring Boot; manual DI |
| 6 | Serialization | **SBE everywhere**: both Aeron and Kafka use SBE |
| 7 | Transport | **Aeron IPC**: shared memory, fastest for demo |
| 8 | State persistence | **In-memory only**: no Redis/RocksDB |
| 9 | Deduplication | **None**: Aeron and Kafka are independent paths |
| 10 | GC | **ZGC**: sub-millisecond pause times |
| 11 | Order amendments | **Not handled**: out of scope |
| 12 | Demo infrastructure | **Shell scripts only**: no Docker/Kubernetes |

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| SBE | Simple Binary Encoding — zero-copy binary serialization |
| ZGC | Z Garbage Collector — low-latency JVM garbage collector |
| IPC | Inter-Process Communication — shared-memory transport in Aeron |
| HDR Histogram | High Dynamic Range Histogram — for accurate latency percentile measurement |
| Tumbling Window | Fixed-size, non-overlapping time window for aggregation |

---

## Appendix B: References

- [Aeron Documentation](https://github.com/real-logic/aeron/wiki)
- [Aeron IPC Guide](https://github.com/real-logic/aeron/wiki/IPC)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [SBE Specification](https://github.com/real-logic/simple-binary-encoding)
- [HDR Histogram](https://github.com/HdrHistogram/HdrHistogram)
- [ZGC Documentation](https://docs.oracle.com/en/java/javase/23/gctuning/z-garbage-collector.html)

---

## Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0.0 | 2026-02-23 | Engineering Team | Initial draft |
| 2.0.0 | 2026-03-17 | Engineering Team | Major revision: plain Java (no Spring Boot), Aeron IPC, SBE everywhere, in-memory only, tumbling windows, per-symbol ratios, REST API as P0, benchmark requirements, resolved all open questions |
