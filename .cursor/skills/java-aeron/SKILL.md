---
name: java-aeron-low-latency
description: Use this skill when the user is writing Java code involving Aeron, low-latency messaging, IPC, UDP unicast/multicast, SBE encoding, or high-performance Java patterns like off-heap memory, busy-spin, mechanical sympathy, or Agrona data structures.
---

# Java + Aeron Low-Latency Skill

## Core Principles
- Always prefer off-heap memory (DirectBuffer, UnsafeBuffer) over heap allocations in the hot path.
- Avoid object allocation in the critical path — use object pooling or pre-allocated buffers.
- Prefer busy-spin polling (FragmentHandler, IdleStrategy) over blocking I/O.
- Use SBE (Simple Binary Encoding) for zero-copy serialization over the wire.

## Aeron Setup
- Use `Aeron.connect()` with a shared `MediaDriver` for production.
- Prefer `EmbeddedMediaDriver` for testing/local only.
- Always close `Aeron`, `Publication`, and `Subscription` in a try-with-resources block.

## Messaging Patterns
- Publication: check `Publication.offer()` return codes — handle `BACK_PRESSURED`, `NOT_CONNECTED`, `ADMIN_ACTION`.
- Subscription: use `Subscription.poll(fragmentHandler, fragmentLimit)` in a tight loop.
- Use `ExclusivePublication` for single-writer, zero-contention scenarios.

## IdleStrategies
- `BusySpinIdleStrategy` — lowest latency, max CPU usage (use in dedicated threads).
- `SleepingIdleStrategy` — best for low-priority background threads.
- `YieldingIdleStrategy` — balance between latency and CPU.

## Agrona Utilities
- Use `ManyToOneConcurrentArrayQueue` or `OneToOneConcurrentArrayQueue` over `java.util.concurrent`.
- Use `UnsafeBuffer` for direct ByteBuffer wrapping without bounds checks in the hot path.

## Code Style
- Always annotate low-latency methods with `@SuppressWarnings("unused")` and document why allocation is avoided.
- Add JVM flags reminder: `-XX:+UseG1GC`, `-XX:MaxGCPauseMillis=10`, `-Daeron.term.buffer.length=...`
- Remind the user to run with `taskset` / CPU affinity when relevant.