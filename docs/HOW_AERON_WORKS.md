# How the Aeron Path Works

A plain-English walkthrough of how trade events flow through the Aeron side of this service.

---

## What is Aeron?

Aeron is a messaging library designed for speed. Think of it as a pipe between two parts of the same program (or two programs on the same machine). Instead of sending messages over the network like Kafka does, Aeron uses **shared memory** (IPC) -- a region of RAM that both the sender and receiver can read and write to directly. This makes it extremely fast because the data never leaves the machine and never touches a network card.

---

## The Big Picture

```
Trade events come in         Results go out
         |                        |
         v                        v
  [Aeron IPC stream 1001]  [Aeron IPC stream 2001]
         |                        ^
         v                        |
  AeronTradeSubscriber -----> RatioEngine -----> AeronRatioPublisher
                                  |
                           InMemoryRatioStore
                           (HashMap in RAM)
```

A trade event arrives on stream 1001. The subscriber picks it up, the engine computes the updated maker/taker ratio, and the publisher pushes the result out on stream 2001. That's it.

---

## Step by Step

### 1. Starting the Media Driver

Before anything can happen, Aeron needs its "router" running. This is called the **Media Driver**. Our class `AeronMediaDriverManager` starts an embedded one inside the same JVM process.

**What it does:**
- Creates a directory on disk (like `/tmp/aeron-ratio`) that acts as shared memory
- Starts the driver in `SHARED` threading mode (one thread handles everything -- good for IPC)
- Uses `BusySpinIdleStrategy` -- the thread never sleeps, it just keeps checking for new data (trades microseconds of CPU for microseconds of latency)

**In simple terms:** It boots up the postal system that Aeron uses to move messages around.

### 2. Receiving Trade Events (`AeronTradeSubscriber`)

This class runs on its own thread. Its job is simple: keep checking for new messages, and when one arrives, decode it and pass it to the engine.

**How it works:**

1. It subscribes to IPC stream 1001 (the input stream)
2. It runs an infinite loop:
   ```
   while (running) {
       poll the subscription for up to 256 messages
       if nothing came, spin-wait (don't sleep -- that's too slow)
   }
   ```
3. When a message arrives, the `onFragment` method fires. It:
   - Reads the SBE header to confirm this is a `TradeExecution` message
   - Decodes the binary bytes back into fields: executionId, userId, symbol, price, quantity, isMaker, etc.
   - Calls `engine.process(...)` with those fields

**What is SBE?** Simple Binary Encoding. Instead of JSON (`{"userId": 42}`), SBE packs data into a compact binary format. A JSON trade message might be 200 bytes; the SBE version is about 60 bytes. More importantly, decoding is a simple pointer offset -- no parsing, no string allocation, no garbage collection pressure.

### 3. Computing the Ratio (`RatioEngine`)

The engine is shared between Aeron and Kafka. It doesn't know or care where the trade came from.

**What it does for every trade:**

1. Look up (or create) the user's **overall** state -- counts across all symbols
2. Record the trade: increment the maker or taker counter in the appropriate time bucket
3. Look up (or create) the user's **per-symbol** state (e.g., user 42 + BTC-USDT)
4. Record the trade there too
5. Publish updated ratios for both (overall and per-symbol)

**Time windows:** The service tracks ratios over 4 rolling windows: 1 hour, 24 hours, 7 days, and 30 days. Each window is split into fixed-size buckets (e.g., 1-hour window = sixty 1-minute buckets). When a bucket expires, its counts are subtracted from the total. This is called a **tumbling window**.

**The ratio itself:** `makerCount / (makerCount + takerCount)`. If a user has 70 maker trades and 30 taker trades, their ratio is 0.7 (70% maker).

### 4. Publishing Results (`AeronRatioPublisher`)

After the engine computes updated ratios, the publisher sends them out on Aeron IPC stream 2001.

**The zero-allocation trick:** On the Aeron path, speed matters most. Instead of creating a snapshot object with arrays (which the garbage collector would later need to clean up), the publisher uses a **visitor pattern**:

1. It calls `state.visit(...)` which locks the state and passes the raw numbers directly as method arguments
2. Inside the visitor callback, it writes those numbers straight into a pre-allocated SBE buffer
3. It offers the buffer to Aeron's publication

No objects are created. No arrays are allocated. No garbage collection happens. This is what "zero-allocation hot path" means.

**What if Aeron pushes back?** If the outgoing buffer is full (`BACK_PRESSURED`) or the receiver isn't connected yet (`NOT_CONNECTED`), the publish returns `false` and the message is dropped. For ratio updates this is fine -- the next trade will produce a fresh, up-to-date ratio anyway.

### 5. Shutdown

When you press Ctrl+C, the shutdown hook in `Application.java` fires:
1. Stops the subscriber (sets `running = false`)
2. Closes the publisher (closes the Aeron publication)
3. Closes the media driver (cleans up the shared memory directory)

---

## Key Aeron Concepts

| Concept | What it means |
|---|---|
| **IPC** | Inter-Process Communication. Messages go through shared memory, not the network. |
| **Stream ID** | A number that identifies a channel. Stream 1001 = input trades, stream 2001 = output ratios. |
| **Publication** | The "write end" of a stream. You offer messages to it. |
| **Subscription** | The "read end" of a stream. You poll messages from it. |
| **ExclusivePublication** | A publication that only one thread can write to. Faster than a regular one because it skips locking. |
| **Media Driver** | The background process that moves bytes between publications and subscriptions. |
| **Fragment** | One message (or piece of a message) returned by a poll. |
| **BusySpinIdleStrategy** | When there's nothing to do, spin the CPU instead of sleeping. Wastes CPU but reacts in nanoseconds. |
| **SBE** | Simple Binary Encoding. A schema-based format where reading a field is just "go to byte offset X and read 8 bytes as a long." |

---

## Why is Aeron Fast?

1. **No network:** IPC uses shared memory on the same machine
2. **No serialization overhead:** SBE is just byte offsets, not parsing
3. **No allocation on hot path:** The visitor pattern avoids creating objects that trigger garbage collection
4. **No sleeping:** BusySpin means the subscriber reacts within microseconds
5. **Pre-allocated buffers:** The send buffer is created once at startup and reused forever

The benchmark shows the Aeron path handling trades at a **p50 of ~2 microseconds** latency when running at 25K messages/second.
