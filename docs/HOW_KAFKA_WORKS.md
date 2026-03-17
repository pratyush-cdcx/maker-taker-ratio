# How the Kafka Path Works

A plain-English walkthrough of how trade events flow through the Kafka side of this service.

---

## What is Kafka?

Apache Kafka is a distributed message broker. Think of it as a durable, append-only log that sits on a server. Producers write messages to a **topic** (like a named mailbox), and consumers read from it. Unlike Aeron which uses shared memory on one machine, Kafka messages travel over the **network** (TCP). This makes it slower but much more flexible -- producers and consumers can be on different machines, and messages are stored on disk so they survive restarts.

---

## The Big Picture

```
Trade events come in                Results go out
         |                                |
         v                                v
  Kafka topic                       Kafka topic
  "trade.executions"                "user.ratio.updates"
         |                                ^
         v                                |
  KafkaTradeConsumer --------> RatioEngine --------> KafkaRatioProducer
                                    |
                             InMemoryRatioStore
                             (HashMap in RAM)
```

A trade event is written to the `trade.executions` topic by some upstream system. Our consumer reads it, the engine computes the updated ratio, and the producer writes the result to the `user.ratio.updates` topic. Downstream services can then read from that topic.

---

## Step by Step

### 1. Consuming Trade Events (`KafkaTradeConsumer`)

This class runs on its own thread. It connects to the Kafka broker, subscribes to the `trade.executions` topic, and processes messages in a loop.

**How it works:**

1. On startup, it creates a `KafkaConsumer` with these settings:
   - `bootstrap.servers`: where to find Kafka (default: `localhost:9092`)
   - `group.id`: `maker-taker-ratio-service` (Kafka uses this to track which messages this consumer has already read)
   - `auto.offset.reset`: `latest` (if this is the first time running, start from new messages, not old ones)
   - `max.poll.records`: 500 (grab up to 500 messages per poll)

2. It subscribes to the topic and enters a loop:
   ```
   while (running) {
       records = consumer.poll(100ms)     // ask Kafka "any new messages?"
       for each record:
           processRecord(record)
   }
   ```

3. For each record, `processRecord` does:
   - Takes the raw `byte[]` value from the Kafka record
   - Wraps it in an SBE decoder buffer
   - Reads the SBE header to confirm it's a `TradeExecution` message
   - Decodes the fields: executionId, userId, symbol, price, quantity, isMaker, etc.
   - Calls `engine.process(...)` with those fields

**What is SBE?** Simple Binary Encoding. Instead of JSON, the messages are packed into a compact binary format defined by a schema. A JSON trade might be 200 bytes; the SBE version is about 60 bytes. Decoding is fast because each field is at a known byte offset -- no parsing needed.

**Graceful shutdown:** When `stop()` is called, it sets `running = false` and calls `consumer.wakeup()`. The `wakeup()` makes the `poll()` throw a `WakeupException`, which breaks out of the loop cleanly. The `finally` block closes the consumer.

### 2. Computing the Ratio (`RatioEngine`)

The engine is shared between Kafka and Aeron. It doesn't know or care where the trade came from.

**What it does for every trade:**

1. Look up (or create) the user's **overall** state -- counts across all symbols
2. Record the trade: increment the maker or taker counter in the appropriate time bucket
3. Look up (or create) the user's **per-symbol** state (e.g., user 42 + BTC-USDT)
4. Record the trade there too
5. Publish updated ratios for both (overall and per-symbol)

**Time windows:** The service tracks ratios over 4 rolling windows: 1 hour, 24 hours, 7 days, and 30 days. Each window is split into fixed-size buckets (e.g., 1-hour window = sixty 1-minute buckets). When a bucket expires, its counts are subtracted from the total. This is called a **tumbling window**.

**The ratio itself:** `makerCount / (makerCount + takerCount)`. If a user has 70 maker trades and 30 taker trades, their ratio is 0.7 (70% maker).

### 3. Publishing Results (`KafkaRatioProducer`)

After the engine computes updated ratios, the producer sends them to the `user.ratio.updates` Kafka topic.

**How it works:**

1. It takes a `WindowSnapshot` -- a small object containing the user's maker counts, taker counts, and ratios for all 4 time windows
2. It encodes this into SBE binary format using a pre-allocated buffer
3. It copies the encoded bytes into a `byte[]` (Kafka's API requires a byte array)
4. It creates a `ProducerRecord` with:
   - **key:** the userId (as a string) -- this ensures all updates for the same user go to the same Kafka partition
   - **value:** the SBE-encoded bytes
5. It calls `producer.send(record, callback)` -- this is **asynchronous**. Kafka batches the message and sends it in the background
6. If sending fails, the callback logs a warning

**Producer tuning:**
- `acks=1`: wait for the leader broker to confirm receipt (not all replicas -- faster)
- `linger.ms=1`: wait up to 1ms to batch multiple messages together before sending
- `batch.size=16384`: batch up to 16KB of messages per send
- `compression=lz4`: compress batches to reduce network usage

**Why does Kafka need a `byte[]` copy?** Unlike Aeron where you can offer a buffer directly, Kafka's producer API takes `byte[]` values. So we have to copy from the SBE encode buffer into a new byte array. This is one reason the Kafka path is slower than Aeron -- it creates objects (the byte array, the ProducerRecord) on every publish.

### 4. Shutdown

When you press Ctrl+C, the shutdown hook fires:
1. Calls `kafkaConsumer.close()` which triggers the wakeup, breaks the poll loop, and commits offsets
2. Calls `kafkaProducer.close()` which flushes any remaining batched messages and disconnects from the broker

---

## Key Kafka Concepts

| Concept | What it means |
|---|---|
| **Topic** | A named log that messages are written to. Like a channel or mailbox. |
| **Partition** | A topic is split into partitions for parallelism. We use 1 partition for simplicity. |
| **Producer** | Writes messages to a topic. |
| **Consumer** | Reads messages from a topic. |
| **Consumer Group** | A named group of consumers. Kafka tracks what each group has read so it doesn't re-deliver. |
| **Offset** | The position of a message in a partition. Like a page number. |
| **Poll** | The consumer asks the broker "give me new messages since my last offset." |
| **Bootstrap Server** | The address of at least one Kafka broker, used to discover the rest of the cluster. |
| **Acks** | How many brokers must confirm a write before the producer considers it successful. |
| **Linger** | How long the producer waits to batch messages before sending. Higher = better throughput, worse latency. |

---

## Kafka vs Aeron -- When to Use Which

| | Kafka | Aeron IPC |
|---|---|---|
| **Where messages go** | Over the network to a broker | Through shared memory on the same machine |
| **Latency** | Milliseconds (network + broker + batching) | Microseconds (memory copy) |
| **Durability** | Messages stored on disk, survive restarts | Gone when the process stops |
| **Scalability** | Multiple machines, partitions, consumer groups | Single machine only |
| **Use case** | Reliable delivery to many downstream services | Ultra-low-latency processing in one process |

In this project, both paths exist side by side. The Aeron path is for when you need the absolute fastest response (e.g., a trading engine that needs the ratio before placing the next order). The Kafka path is for when you need reliability and fan-out (e.g., a dashboard service, an analytics pipeline, or an audit log that all need the ratio updates).

---

## How to Run It

1. Start Kafka: `./scripts/start-kafka.sh` (downloads and runs a local broker)
2. Build: `./scripts/build.sh`
3. Run: `./scripts/run.sh`
4. Benchmark: `./scripts/run-benchmark.sh kafka`

The Kafka benchmark sends 100K messages through the full pipeline (produce trade -> consume -> compute ratio -> produce ratio -> consume ratio) and measures the end-to-end latency at each percentile.
