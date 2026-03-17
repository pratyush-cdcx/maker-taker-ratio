# Technical Reference Document (TRD)

## User Maker/Taker Ratio Service

| Field | Value |
|-------|-------|
| **Version** | 1.0.0 |
| **Status** | Approved |
| **Date** | March 17, 2026 |

---

## Table of Contents

**Part I — High-Level Design (HLD)**
1. [System Context](#1-system-context)
2. [Component Architecture](#2-component-architecture)
3. [Data Flow](#3-data-flow)
4. [SBE Message Schemas](#4-sbe-message-schemas)

**Part II — Low-Level Design (LLD)**
5. [Package Structure](#5-package-structure)
6. [Class Design](#6-class-design)
7. [Tumbling Window Algorithm](#7-tumbling-window-algorithm)
8. [Threading Model](#8-threading-model)
9. [Allocation-Free Hot Path](#9-allocation-free-hot-path)
10. [REST Server Design](#10-rest-server-design)
11. [Benchmark Harness Design](#11-benchmark-harness-design)

**Part III — Operational Concerns**
12. [Error Handling](#12-error-handling)
13. [Graceful Shutdown](#13-graceful-shutdown)
14. [JVM Tuning](#14-jvm-tuning)
15. [Test Plan](#15-test-plan)
16. [Benchmark Methodology](#16-benchmark-methodology)

---

# Part I — High-Level Design (HLD)

## 1. System Context

```
┌───────────────────┐         ┌────────────────────────────────┐         ┌───────────────────┐
│  Matching Engine  │         │   Maker/Taker Ratio Service    │         │   Fee Service     │
│                   │         │                                │         │                   │
│  Produces trade   │─Aeron──▶│  Aeron IPC Sub (stream 1001)  │─Aeron──▶│  Aeron IPC Sub    │
│  execution events │  IPC    │                                │  IPC    │  (stream 2001)    │
│                   │         │         Ratio Engine           │         │                   │
│                   │─Kafka──▶│  Kafka Consumer                │─Kafka──▶│  Kafka Consumer   │
│                   │         │  (trade.executions)            │         │  (user.ratio.     │
│                   │         │                                │         │   updates)        │
└───────────────────┘         │         REST API (:8080)       │         └───────────────────┘
                              │  GET /api/v1/users/{id}/ratio  │
                              └────────────────────────────────┘
                                            │
                              ┌──────────────▼──────────────┐
                              │  Benchmark Clients          │
                              │  (AeronBenchmark,           │
                              │   KafkaBenchmark)           │
                              └─────────────────────────────┘
```

The service sits between the matching engine (upstream producer of trade events) and downstream consumers (fee service, analytics, risk). It operates two completely independent data paths:

- **Aeron IPC path**: Ultra-low-latency, shared-memory transport for co-located processes.
- **Kafka path**: Durable, distributed transport for cross-machine consumers.

Both paths feed into the same `RatioEngine` and share the same in-memory state store. There is no deduplication between paths.

---

## 2. Component Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            Application                                  │
│                                                                         │
│  ┌─────────────────────┐    ┌──────────────────┐    ┌────────────────┐ │
│  │  AeronTradeSubscriber│    │   RatioEngine    │    │AeronRatioPublisher│
│  │  (Thread: aeron-sub) │───▶│   (stateless)    │───▶│  (ExclusivePub)│ │
│  └─────────────────────┘    │                  │    └────────────────┘ │
│                              │  classify()      │                      │
│  ┌─────────────────────┐    │  updateState()   │    ┌────────────────┐ │
│  │  KafkaTradeConsumer │───▶│  computeRatios() │───▶│KafkaRatioProducer│
│  │  (Thread: kafka-con) │    │                  │    │  (async send)  │ │
│  └─────────────────────┘    └────────┬─────────┘    └────────────────┘ │
│                                      │                                  │
│                              ┌───────▼──────────┐                      │
│                              │ InMemoryRatioStore│                      │
│                              │ (ConcurrentHashMap)│                     │
│                              └──────────────────┘                      │
│                                                                         │
│  ┌──────────────────┐    ┌──────────────────────┐                      │
│  │  RatioRestServer │───▶│  InMemoryRatioStore  │                      │
│  │  (Thread pool)   │    │  (read-only access)  │                      │
│  └──────────────────┘    └──────────────────────┘                      │
│                                                                         │
│  ┌──────────────────┐                                                  │
│  │ AeronMediaDriver │  (EmbeddedMediaDriver, shared /dev/shm)         │
│  │ Manager           │                                                  │
│  └──────────────────┘                                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Thread | Responsibility |
|-----------|--------|----------------|
| `AeronMediaDriverManager` | Main (startup) | Launches/connects to embedded media driver |
| `AeronTradeSubscriber` | `aeron-subscriber` | Busy-spin polls Aeron IPC subscription; decodes SBE; calls `RatioEngine` |
| `KafkaTradeConsumer` | `kafka-consumer` | Polls Kafka topic; decodes SBE; calls `RatioEngine` |
| `RatioEngine` | Caller's thread | Stateless processor: classify, update store, compute ratios, build output |
| `InMemoryRatioStore` | Shared (thread-safe) | `ConcurrentHashMap`-based store for per-user and per-user-symbol state |
| `AeronRatioPublisher` | Caller's thread | Encodes ratio update via SBE; offers to `ExclusivePublication` |
| `KafkaRatioProducer` | Caller's thread + Kafka I/O | Encodes ratio update via SBE; sends to Kafka producer |
| `RatioRestServer` | HTTP thread pool | Serves REST queries from in-memory store |

---

## 3. Data Flow

### 3.1 Aeron Path (Low-Latency)

```
Benchmark/MatchingEngine                  Ratio Service                     Downstream
        │                                      │                                │
        │  SBE(TradeExecution)                 │                                │
        ├──── Aeron IPC offer() ──────────────▶│                                │
        │     stream 1001                      │                                │
        │                                      │  1. poll() → FragmentHandler   │
        │                                      │  2. SBE decode (zero-copy)     │
        │                                      │  3. RatioEngine.process()      │
        │                                      │  4. Update InMemoryRatioStore  │
        │                                      │  5. SBE encode RatioUpdate     │
        │                                      │  6. Aeron IPC offer()          │
        │                                      ├──── stream 2001 ──────────────▶│
        │                                      │  7. Kafka send()               │
        │                                      ├──── user.ratio.updates ───────▶│
```

### 3.2 Kafka Path (High-Throughput)

```
Benchmark/MatchingEngine                  Ratio Service                     Downstream
        │                                      │                                │
        │  SBE(TradeExecution)                 │                                │
        ├──── Kafka produce() ────────────────▶│                                │
        │     trade.executions                 │                                │
        │                                      │  1. poll() → ConsumerRecords   │
        │                                      │  2. SBE decode                 │
        │                                      │  3. RatioEngine.process()      │
        │                                      │  4. Update InMemoryRatioStore  │
        │                                      │  5. SBE encode RatioUpdate     │
        │                                      │  6. Aeron IPC offer()          │
        │                                      ├──── stream 2001 ──────────────▶│
        │                                      │  7. Kafka send()               │
        │                                      ├──── user.ratio.updates ───────▶│
```

---

## 4. SBE Message Schemas

### 4.1 Schema File Structure

```
src/main/resources/sbe/
├── schema.xml          # Message schema with types, enums, messages
```

### 4.2 TradeExecution Message (ID=1)

| Field | ID | Type | Description |
|-------|----|------|-------------|
| executionId | 1 | int64 | Unique execution identifier |
| orderId | 2 | int64 | Order identifier |
| userId | 3 | int64 | User identifier |
| side | 4 | Side (uint8) | BUY=0, SELL=1 |
| price | 5 | int64 | Price in minor units (e.g., cents) |
| quantity | 6 | int64 | Quantity in minor units |
| timestamp | 7 | int64 | Epoch milliseconds |
| isMaker | 8 | BooleanType (uint8) | FALSE=0, TRUE=1 |
| symbol | 9 | varStringEncoding | Trading pair (e.g., "BTC-USDT") |

### 4.3 RatioUpdate Message (ID=2)

A single flat message containing ratios for all 4 time windows:

| Field | ID | Type | Description |
|-------|----|------|-------------|
| userId | 1 | int64 | User identifier |
| timestamp | 2 | int64 | Update timestamp (epoch ms) |
| makerCount1H | 3 | int64 | Maker count in 1H window |
| takerCount1H | 4 | int64 | Taker count in 1H window |
| ratio1H | 5 | double | Maker ratio for 1H window |
| makerCount24H | 6 | int64 | Maker count in 24H window |
| takerCount24H | 7 | int64 | Taker count in 24H window |
| ratio24H | 8 | double | Maker ratio for 24H window |
| makerCount7D | 9 | int64 | Maker count in 7D window |
| takerCount7D | 10 | int64 | Taker count in 7D window |
| ratio7D | 11 | double | Maker ratio for 7D window |
| makerCount30D | 12 | int64 | Maker count in 30D window |
| takerCount30D | 13 | int64 | Taker count in 30D window |
| ratio30D | 14 | double | Maker ratio for 30D window |
| symbol | 15 | varStringEncoding | Trading pair (empty string = overall) |

---

# Part II — Low-Level Design (LLD)

## 5. Package Structure

```
src/main/java/com/coindcx/makertaker/
├── Application.java                    # Main entry point; wires all components
├── config/
│   └── AppConfig.java                  # Loads configuration from properties/env
├── model/
│   ├── TimeWindow.java                 # Enum: HOUR_1, HOUR_24, DAY_7, DAY_30
│   ├── RatioKey.java                   # Record: (userId, symbol) composite key
│   ├── TumblingBucket.java             # Mutable bucket: startTime, makerCount, takerCount
│   ├── WindowState.java                # State for one time window: circular buffer of buckets
│   └── UserSymbolState.java            # Full state for a user-symbol pair (4 WindowStates)
├── engine/
│   ├── RatioEngine.java                # Core processing: classify → update → compute → build output
│   └── TumblingWindowManager.java      # Manages bucket rotation and expiry per window
├── state/
│   └── InMemoryRatioStore.java         # ConcurrentHashMap-based thread-safe store
├── aeron/
│   ├── AeronMediaDriverManager.java    # Lifecycle management for EmbeddedMediaDriver
│   ├── AeronTradeSubscriber.java       # Polls Aeron IPC subscription in busy-spin loop
│   └── AeronRatioPublisher.java        # Publishes ratio updates via ExclusivePublication
├── kafka/
│   ├── KafkaTradeConsumer.java         # Polls Kafka topic, SBE decode
│   └── KafkaRatioProducer.java         # Produces to Kafka, SBE encode
├── rest/
│   └── RatioRestServer.java            # com.sun.net.httpserver-based REST endpoint
└── benchmark/
    ├── AeronBenchmark.java             # Aeron IPC latency + throughput benchmark
    ├── KafkaBenchmark.java             # Kafka latency + throughput benchmark
    └── BenchmarkResult.java            # Holds and formats benchmark results

src/main/resources/
├── sbe/
│   └── schema.xml                      # SBE message schema
├── application.properties              # Default configuration
└── logback.xml                         # Logging configuration

src/test/java/com/coindcx/makertaker/
├── model/
│   ├── TimeWindowTest.java
│   ├── TumblingBucketTest.java
│   ├── WindowStateTest.java
│   └── UserSymbolStateTest.java
├── engine/
│   ├── RatioEngineTest.java
│   └── TumblingWindowManagerTest.java
├── state/
│   └── InMemoryRatioStoreTest.java
├── aeron/
│   ├── AeronTradeSubscriberTest.java
│   └── AeronRatioPublisherTest.java
├── kafka/
│   ├── KafkaTradeConsumerTest.java
│   └── KafkaRatioProducerTest.java
├── rest/
│   └── RatioRestServerTest.java
├── sbe/
│   └── SbeCodecTest.java
└── integration/
    ├── AeronEndToEndTest.java
    └── KafkaEndToEndTest.java
```

---

## 6. Class Design

### 6.1 `AppConfig`

```java
public final class AppConfig {
    // Aeron
    private String aeronDir;
    private int aeronInputStreamId;
    private int aeronOutputStreamId;

    // Kafka
    private String kafkaBootstrapServers;
    private String kafkaInputTopic;
    private String kafkaOutputTopic;
    private String kafkaGroupId;

    // REST
    private int restPort;

    // Factory method
    public static AppConfig load();           // from system properties + env vars
    public static AppConfig defaults();       // sensible defaults for testing
}
```

### 6.2 `TimeWindow`

```java
public enum TimeWindow {
    HOUR_1(Duration.ofHours(1), Duration.ofMinutes(1), 60),
    HOUR_24(Duration.ofHours(24), Duration.ofMinutes(5), 288),
    DAY_7(Duration.ofDays(7), Duration.ofHours(1), 168),
    DAY_30(Duration.ofDays(30), Duration.ofHours(1), 720);

    private final Duration windowDuration;
    private final Duration bucketDuration;
    private final int bucketCount;
}
```

### 6.3 `TumblingBucket`

```java
public final class TumblingBucket {
    private long bucketStartEpochMs;
    private long makerCount;
    private long takerCount;

    public void reset(long newStartEpochMs);
    public void incrementMaker();
    public void incrementTaker();
}
```

### 6.4 `WindowState`

```java
public final class WindowState {
    private final TimeWindow window;
    private final TumblingBucket[] buckets;    // circular buffer
    private int headIndex;                      // index of current (newest) bucket
    private long totalMakerCount;               // cached aggregate
    private long totalTakerCount;               // cached aggregate

    public WindowState(TimeWindow window);
    public void record(long eventTimestampMs, boolean isMaker);
    public void expireOldBuckets(long currentTimeMs);
    public long getMakerCount();
    public long getTakerCount();
    public double getRatio();
}
```

### 6.5 `UserSymbolState`

```java
public final class UserSymbolState {
    private final long userId;
    private final String symbol;               // null for overall user ratio
    private final WindowState[] windows;        // indexed by TimeWindow.ordinal()
    private long lastUpdatedTimestamp;

    public UserSymbolState(long userId, String symbol);
    public void record(long timestampMs, boolean isMaker);
    public double getRatio(TimeWindow window);
    public long getMakerCount(TimeWindow window);
    public long getTakerCount(TimeWindow window);
}
```

### 6.6 `InMemoryRatioStore`

```java
public final class InMemoryRatioStore {
    private final ConcurrentHashMap<Long, UserSymbolState> overallStore;
    private final ConcurrentHashMap<RatioKey, UserSymbolState> perSymbolStore;

    public UserSymbolState getOrCreateOverall(long userId);
    public UserSymbolState getOrCreatePerSymbol(long userId, String symbol);
    public UserSymbolState getOverall(long userId);              // nullable
    public UserSymbolState getPerSymbol(long userId, String symbol); // nullable
}
```

### 6.7 `RatioEngine`

```java
public final class RatioEngine {
    private final InMemoryRatioStore store;
    private final AeronRatioPublisher aeronPublisher;   // nullable
    private final KafkaRatioProducer kafkaProducer;     // nullable

    public void process(long executionId, long orderId, long userId,
                        String symbol, int side, long price, long quantity,
                        long timestamp, boolean isMaker);
}
```

The `process()` method:
1. Gets or creates `UserSymbolState` for both overall and per-symbol.
2. Calls `state.record(timestamp, isMaker)` on both.
3. Builds a `RatioUpdate` output (SBE-encoded) with all 4 window ratios.
4. Publishes to Aeron (if publisher non-null).
5. Publishes to Kafka (if producer non-null).

### 6.8 `AeronTradeSubscriber`

```java
public final class AeronTradeSubscriber implements Runnable, AutoCloseable {
    private final Subscription subscription;
    private final RatioEngine engine;
    private final IdleStrategy idleStrategy;    // BusySpinIdleStrategy
    private volatile boolean running = true;

    // Pre-allocated decoder (no allocation in hot path)
    private final TradeExecutionDecoder decoder = new TradeExecutionDecoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    @Override
    public void run();   // poll loop
    public void close(); // sets running = false
}
```

### 6.9 `AeronRatioPublisher`

```java
public final class AeronRatioPublisher implements AutoCloseable {
    private final ExclusivePublication publication;

    // Pre-allocated encoder + buffer (allocation-free hot path)
    private final UnsafeBuffer buffer;
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final RatioUpdateEncoder encoder = new RatioUpdateEncoder();

    public boolean publish(long userId, String symbol, long timestamp,
                          UserSymbolState state);
}
```

### 6.10 `KafkaTradeConsumer`

```java
public final class KafkaTradeConsumer implements Runnable, AutoCloseable {
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final RatioEngine engine;
    private volatile boolean running = true;

    private final TradeExecutionDecoder decoder = new TradeExecutionDecoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    @Override
    public void run();   // poll loop
    public void close(); // wakeup + sets running = false
}
```

### 6.11 `KafkaRatioProducer`

```java
public final class KafkaRatioProducer implements AutoCloseable {
    private final KafkaProducer<byte[], byte[]> producer;
    private final String topic;

    private final UnsafeBuffer buffer;
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final RatioUpdateEncoder encoder = new RatioUpdateEncoder();

    public void publish(long userId, String symbol, long timestamp,
                       UserSymbolState state);
}
```

### 6.12 `RatioRestServer`

```java
public final class RatioRestServer implements AutoCloseable {
    private final HttpServer server;
    private final InMemoryRatioStore store;
    private final Gson gson;

    public RatioRestServer(int port, InMemoryRatioStore store);
    public void start();
    public void stop();

    // Handles: GET /api/v1/users/{userId}/ratio?window=24H&symbol=BTC-USDT
    private void handleGetRatio(HttpExchange exchange);
}
```

### 6.13 `Application` (Main)

```java
public final class Application {
    public static void main(String[] args) {
        AppConfig config = AppConfig.load();

        // 1. Start Aeron media driver
        AeronMediaDriverManager driverMgr = new AeronMediaDriverManager(config);
        driverMgr.start();

        // 2. Create state store
        InMemoryRatioStore store = new InMemoryRatioStore();

        // 3. Create publishers
        AeronRatioPublisher aeronPub = new AeronRatioPublisher(driverMgr, config);
        KafkaRatioProducer kafkaProd = new KafkaRatioProducer(config);

        // 4. Create engine
        RatioEngine engine = new RatioEngine(store, aeronPub, kafkaProd);

        // 5. Create consumers
        AeronTradeSubscriber aeronSub = new AeronTradeSubscriber(driverMgr, config, engine);
        KafkaTradeConsumer kafkaCon = new KafkaTradeConsumer(config, engine);

        // 6. Start REST server
        RatioRestServer restServer = new RatioRestServer(config.getRestPort(), store);
        restServer.start();

        // 7. Start consumer threads
        Thread aeronThread = new Thread(aeronSub, "aeron-subscriber");
        Thread kafkaThread = new Thread(kafkaCon, "kafka-consumer");
        aeronThread.start();
        kafkaThread.start();

        // 8. Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            aeronSub.close();
            kafkaCon.close();
            restServer.stop();
            kafkaProd.close();
            aeronPub.close();
            driverMgr.close();
        }));
    }
}
```

---

## 7. Tumbling Window Algorithm

### 7.1 Concept

Each `TimeWindow` maintains a circular buffer of `TumblingBucket`s. For example, `HOUR_1` uses 60 buckets of 1-minute each.

```
Time:     |  00:00  |  00:01  |  00:02  | ... |  00:59  |
Buckets:  | Bucket0 | Bucket1 | Bucket2 | ... | Bucket59|
           ▲ head (oldest)                       ▲ tail (newest)
```

### 7.2 Recording a Trade

```
record(eventTimestampMs, isMaker):
    1. Compute target bucket index:
       bucketIndex = (eventTimestampMs / bucketDurationMs) % bucketCount

    2. If target bucket is expired (belongs to a previous window cycle):
       - Reset the bucket (set counts to 0, update startTime)
       - Subtract old bucket's counts from totals
       - Expire any buckets between the old head and this new bucket

    3. Increment makerCount or takerCount in target bucket

    4. Update cached totals (totalMakerCount / totalTakerCount)
```

### 7.3 Bucket Expiry

```
expireOldBuckets(currentTimeMs):
    cutoffTime = currentTimeMs - windowDurationMs

    for each bucket in circular buffer:
        if bucket.bucketStartEpochMs < cutoffTime:
            totalMakerCount -= bucket.makerCount
            totalTakerCount -= bucket.takerCount
            bucket.reset(0)  // mark as empty
```

### 7.4 Bucket Sizing

| Window | Bucket Duration | Bucket Count | Memory per User-Symbol |
|--------|----------------|--------------|----------------------|
| 1H | 1 minute | 60 | 60 * 24 bytes = 1.4 KB |
| 24H | 5 minutes | 288 | 288 * 24 bytes = 6.8 KB |
| 7D | 1 hour | 168 | 168 * 24 bytes = 3.9 KB |
| 30D | 1 hour | 720 | 720 * 24 bytes = 16.9 KB |

Total per user-symbol pair: ~29 KB across all 4 windows.
For 100K active user-symbol pairs: ~2.8 GB memory.

---

## 8. Threading Model

```
┌──────────────────────────────────────────────────────────────┐
│                         JVM Process                           │
│                                                               │
│  Thread: "main"                                               │
│    └── Application.main() → wire components, start threads    │
│                                                               │
│  Thread: "aeron-subscriber" (daemon)                          │
│    └── BusySpinIdleStrategy poll loop                         │
│        └── subscription.poll(handler, 10)                     │
│            └── RatioEngine.process()                          │
│                ├── store.getOrCreate() [ConcurrentHashMap]    │
│                ├── state.record() [synchronized per state]    │
│                ├── aeronPublisher.publish() [thread-local buf]│
│                └── kafkaProducer.publish() [async send]       │
│                                                               │
│  Thread: "kafka-consumer" (daemon)                            │
│    └── while(running) KafkaConsumer.poll(100ms)               │
│        └── for each record: RatioEngine.process()             │
│            ├── store.getOrCreate() [ConcurrentHashMap]        │
│            ├── state.record() [synchronized per state]        │
│            ├── aeronPublisher.publish() [thread-local buf]    │
│            └── kafkaProducer.publish() [async send]           │
│                                                               │
│  Thread: "kafka-producer-io" (Kafka internal)                 │
│    └── Batches and sends records to Kafka broker              │
│                                                               │
│  Thread pool: "http-server" (2-4 threads)                     │
│    └── Handle REST requests (read-only from store)            │
│                                                               │
│  Thread: "aeron-conductor" (MediaDriver internal)             │
│    └── Aeron media driver housekeeping                        │
│                                                               │
│  Thread: "shutdown-hook"                                      │
│    └── Graceful shutdown sequence                             │
└──────────────────────────────────────────────────────────────┘
```

### Thread Safety Strategy

The `UserSymbolState` objects are accessed by both the Aeron and Kafka consumer threads. Thread safety is achieved by:

1. **`ConcurrentHashMap`** for the store itself — safe concurrent `computeIfAbsent`.
2. **`synchronized` on individual `UserSymbolState`** — since the same user-symbol state could be updated by both Aeron and Kafka threads simultaneously, each `record()` call synchronizes on the state object. This is acceptable because:
   - Contention is low (two threads, hash-distributed across many keys)
   - Critical section is tiny (increment counters + update totals)

---

## 9. Allocation-Free Hot Path

### 9.1 Principles

On the Aeron path, we target zero allocation per event to avoid GC pressure:

1. **SBE decoders**: Wrap the incoming `DirectBuffer` — no object creation.
2. **SBE encoders**: Pre-allocated `UnsafeBuffer` + encoder instances as fields.
3. **No String allocation for symbol**: Use `CharSequence` view from SBE where possible; only create `String` for HashMap key (unavoidable but cached).
4. **No autoboxing**: Use primitive `long` keys where possible (overall store keyed by `long userId`).
5. **Pre-sized collections**: `ConcurrentHashMap` initialized with expected capacity.

### 9.2 Buffer Management

```
Per AeronTradeSubscriber:
  - headerDecoder (field, reused)
  - tradeDecoder (field, reused)
  - wraps incoming DirectBuffer — no copy

Per AeronRatioPublisher:
  - UnsafeBuffer (field, 512 bytes, pre-allocated)
  - headerEncoder (field, reused)
  - ratioEncoder (field, reused)

Per KafkaRatioProducer:
  - UnsafeBuffer (field, 512 bytes, pre-allocated)
  - headerEncoder (field, reused)
  - ratioEncoder (field, reused)
  - byte[] for Kafka ProducerRecord (sized once, reused via System.arraycopy)
```

---

## 10. REST Server Design

### 10.1 Endpoint

```
GET /api/v1/users/{userId}/ratio?window={window}&symbol={symbol}
```

### 10.2 Implementation

Uses `com.sun.net.httpserver.HttpServer` from the JDK:

- Bound to port 8080 (configurable).
- Thread pool of 4 threads (virtual threads on Java 23).
- Request parsing: extract `userId` from path, `window`/`symbol` from query params.
- Response: JSON via Gson (not on hot path, so allocation is fine).
- Error handling: 400 for bad params, 404 for unknown user, 500 for internal errors.

### 10.3 Response Format

All windows (default):
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

---

## 11. Benchmark Harness Design

### 11.1 Architecture

```
┌────────────────────┐         ┌────────────────────┐
│  BenchmarkClient   │────────▶│  Ratio Service     │────────▶ RatioUpdate
│  (publishes trades)│         │  (processes)        │          (received by client)
└────────────────────┘         └────────────────────┘
         │                                                         │
         └─────── timestamp embedded ──────────────────────────────┘
                  in each trade event     latency = receiveTime - sendTime
```

### 11.2 Aeron Benchmark

```java
public final class AeronBenchmark {
    // 1. Start embedded MediaDriver + Aeron
    // 2. Create Publication (stream 1001) + Subscription (stream 2001)
    // 3. Start Ratio Service (in-process or separate process)
    // 4. Warm-up: send 10K trades, discard results
    // 5. Measurement: send N trades (configurable, default 100K)
    //    - Record send timestamp in trade event
    //    - On receiving ratio update, compute latency = now - timestamp
    //    - Record in HDR Histogram
    // 6. Output: p50, p90, p99, p999, max latency + throughput
}
```

### 11.3 Kafka Benchmark

```java
public final class KafkaBenchmark {
    // 1. Create KafkaProducer + KafkaConsumer (for ratio updates)
    // 2. Start Ratio Service (in-process)
    // 3. Warm-up: send 10K trades, discard results
    // 4. Measurement: send N trades
    //    - Record send timestamp in trade event
    //    - Consumer polls ratio updates, computes latency
    //    - Record in HDR Histogram
    // 5. Output: p50, p90, p99, p999, max latency + throughput
}
```

### 11.4 Comparison Output

```
╔═══════════════════════════════════════════════════════════════╗
║          Maker/Taker Ratio Service — Benchmark Results       ║
╠═══════════════════╦══════════════╦═══════════════════════════╣
║ Metric            ║ Aeron IPC    ║ Kafka                     ║
╠═══════════════════╬══════════════╬═══════════════════════════╣
║ Messages          ║ 100,000      ║ 100,000                   ║
║ Throughput (msg/s)║ 850,000      ║ 45,000                    ║
║ Latency p50       ║ 2 μs        ║ 1.2 ms                    ║
║ Latency p90       ║ 5 μs        ║ 3.5 ms                    ║
║ Latency p99       ║ 15 μs       ║ 8.2 ms                    ║
║ Latency p999      ║ 50 μs       ║ 12.5 ms                   ║
║ Latency max       ║ 200 μs      ║ 45 ms                     ║
╚═══════════════════╩══════════════╩═══════════════════════════╝
```

---

# Part III — Operational Concerns

## 12. Error Handling

### 12.1 Aeron Path

| Error | Handling |
|-------|----------|
| `Publication.offer()` returns `BACK_PRESSURED` | Retry with idle strategy; log warning if sustained |
| `Publication.offer()` returns `NOT_CONNECTED` | Log error; skip publish (non-fatal) |
| `Publication.offer()` returns `ADMIN_ACTION` | Retry once |
| SBE decode error | Log error with hex dump; skip message |
| Subscription closed | Exit poll loop; trigger shutdown |

### 12.2 Kafka Path

| Error | Handling |
|-------|----------|
| `KafkaConsumer.poll()` throws `WakeupException` | Expected during shutdown; exit loop |
| Deserialization error | Log error; skip record; commit offset |
| `KafkaProducer.send()` callback error | Log error; metric increment; continue |
| Broker unavailable | Kafka client retries automatically; log warnings |

### 12.3 REST Path

| Error | Handling |
|-------|----------|
| Invalid userId | 400 Bad Request with error message |
| User not found | 404 Not Found |
| Internal error | 500 Internal Server Error; log stack trace |

---

## 13. Graceful Shutdown

```
Signal SIGTERM / SIGINT received
         │
         ▼
1. Set running = false on AeronTradeSubscriber
   └── Poll loop exits naturally
         │
         ▼
2. Call KafkaTradeConsumer.close()
   └── consumer.wakeup() → WakeupException → loop exits
   └── consumer.close() → commits offsets
         │
         ▼
3. Stop RatioRestServer
   └── httpServer.stop(5) → 5 second drain
         │
         ▼
4. Close KafkaRatioProducer
   └── producer.flush() → send pending
   └── producer.close()
         │
         ▼
5. Close AeronRatioPublisher
   └── publication.close()
         │
         ▼
6. Close AeronMediaDriverManager
   └── aeron.close()
   └── mediaDriver.close()
         │
         ▼
7. JVM exits cleanly
```

Maximum shutdown time: 10 seconds (5s REST drain + 5s Kafka flush).

---

## 14. JVM Tuning

### 14.1 Recommended JVM Flags

```bash
java \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms4g -Xmx4g \
  -XX:+AlwaysPreTouch \
  -XX:+UseTransparentHugePages \
  -Daeron.term.buffer.length=16777216 \
  -Daeron.ipc.term.buffer.length=16777216 \
  -Daeron.socket.so_sndbuf=2097152 \
  -Daeron.socket.so_rcvbuf=2097152 \
  -Daeron.threading.mode=SHARED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -jar maker-taker-ratio-service.jar
```

### 14.2 Flag Explanations

| Flag | Purpose |
|------|---------|
| `-XX:+UseZGC` | Z Garbage Collector for sub-ms pauses |
| `-XX:+ZGenerational` | Generational ZGC (Java 21+) for better throughput |
| `-Xms4g -Xmx4g` | Fixed heap to avoid resizing |
| `-XX:+AlwaysPreTouch` | Pre-fault heap pages at startup |
| `-XX:+UseTransparentHugePages` | Reduce TLB misses |
| `aeron.term.buffer.length` | 16MB term buffers for higher throughput |
| `aeron.threading.mode=SHARED` | Single conductor thread (simpler for IPC) |

### 14.3 CPU Affinity (Optional, for lowest latency)

```bash
# Pin aeron-subscriber thread to CPU core 2
taskset -c 2 java ... -Daeron.subscriber.cpu.affinity=2

# Pin kafka-consumer thread to CPU core 3
# (requires custom thread factory or code-level pinning)
```

---

## 15. Test Plan

### 15.1 Coverage Target: 95%

| Class | Test Class | Key Test Cases |
|-------|-----------|---------------|
| `TimeWindow` | `TimeWindowTest` | Enum values, durations, bucket counts |
| `TumblingBucket` | `TumblingBucketTest` | Increment, reset, boundary values |
| `WindowState` | `WindowStateTest` | Record trades, bucket rotation, expiry, ratio calculation, empty state |
| `UserSymbolState` | `UserSymbolStateTest` | Multi-window recording, ratio computation, concurrent access |
| `InMemoryRatioStore` | `InMemoryRatioStoreTest` | Get/create overall, get/create per-symbol, concurrent access, null returns |
| `RatioEngine` | `RatioEngineTest` | Full processing pipeline, maker classification, taker classification, all windows updated, publisher/producer called |
| `TumblingWindowManager` | `TumblingWindowManagerTest` | Bucket index calculation, rotation, expiry across window boundary |
| `AeronTradeSubscriber` | `AeronTradeSubscriberTest` | SBE decode, engine invocation, shutdown (uses EmbeddedMediaDriver) |
| `AeronRatioPublisher` | `AeronRatioPublisherTest` | SBE encode, publication offer, back-pressure handling |
| `KafkaTradeConsumer` | `KafkaTradeConsumerTest` | SBE decode from Kafka record, engine invocation, shutdown |
| `KafkaRatioProducer` | `KafkaRatioProducerTest` | SBE encode, Kafka send, error callback |
| `RatioRestServer` | `RatioRestServerTest` | GET all windows, GET single window, GET with symbol, 404, 400 |
| `SbeCodecTest` | `SbeCodecTest` | Round-trip encode/decode for TradeExecution and RatioUpdate |
| `AppConfig` | `AppConfigTest` | Load from properties, env var override, defaults |

### 15.2 Integration Tests

| Test | Description | Infrastructure |
|------|-------------|---------------|
| `AeronEndToEndTest` | Publish trade via Aeron IPC → verify ratio update received on output stream | EmbeddedMediaDriver |
| `KafkaEndToEndTest` | Produce trade to Kafka → verify ratio update on output topic | Embedded Kafka or Testcontainers |

### 15.3 JaCoCo Configuration

```kotlin
// build.gradle.kts
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}
```

---

## 16. Benchmark Methodology

### 16.1 Warm-Up

- 10,000 messages discarded before measurement begins.
- Ensures JIT compilation, buffer allocation, and Kafka connection warm-up.

### 16.2 Measurement

- Default: 100,000 messages per run.
- Timestamps embedded in each `TradeExecution` event.
- Latency = `System.nanoTime()` at receive - `System.nanoTime()` at send (for Aeron; for Kafka, use epoch ms difference).
- HDR Histogram with 3 significant digits, range 1 μs to 10 seconds.

### 16.3 Throughput Measurement

- Send all messages as fast as possible (no pacing).
- Throughput = total messages / elapsed wall-clock time.

### 16.4 Environment Requirements

- Single machine (Aeron IPC requires shared memory).
- Kafka broker running locally (for Kafka benchmark).
- No other CPU-intensive processes during benchmark.
- At least 2 free CPU cores.

### 16.5 Reporting

Results output to stdout in table format (see section 11.4) and optionally to a JSON file for programmatic consumption.

---

## Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0.0 | 2026-03-17 | Engineering Team | Initial TRD |
