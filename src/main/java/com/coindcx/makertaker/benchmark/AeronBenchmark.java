package com.coindcx.makertaker.benchmark;

import com.coindcx.makertaker.aeron.AeronMediaDriverManager;
import com.coindcx.makertaker.aeron.AeronRatioPublisher;
import com.coindcx.makertaker.aeron.AeronTradeSubscriber;
import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.sbe.BooleanType;
import com.coindcx.makertaker.sbe.MessageHeaderDecoder;
import com.coindcx.makertaker.sbe.MessageHeaderEncoder;
import com.coindcx.makertaker.sbe.RatioUpdateDecoder;
import com.coindcx.makertaker.sbe.SideEnum;
import com.coindcx.makertaker.sbe.TradeExecutionEncoder;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.HdrHistogram.Histogram;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AeronBenchmark {
    private static final int WARMUP_COUNT = 10_000;
    private static final int DEFAULT_MESSAGE_COUNT = 100_000;
    private static final int BUFFER_SIZE = 256;
    private static final int RESPONSES_PER_TRADE = 2;

    public static BenchmarkResult run(int messageCount) throws Exception {
        return run(messageCount, 0);
    }

    /**
     * @param targetRps target messages/sec; 0 = burst (fire-as-fast-as-possible)
     */
    public static BenchmarkResult run(int messageCount, int targetRps) throws Exception {
        String mode = targetRps > 0 ? "Paced@" + targetRps + "rps" : "Burst";
        AppConfig config = AppConfig.defaults();
        AeronMediaDriverManager driverMgr = new AeronMediaDriverManager(config);

        try {
            InMemoryRatioStore store = new InMemoryRatioStore();

            ExclusivePublication outPub = driverMgr.aeron().addExclusivePublication(
                "aeron:ipc", config.getAeronOutputStreamId());
            AeronRatioPublisher ratioPublisher = new AeronRatioPublisher(outPub);

            RatioEngine engine = new RatioEngine(store, ratioPublisher, null);

            Subscription inSub = driverMgr.aeron().addSubscription(
                "aeron:ipc", config.getAeronInputStreamId());
            AeronTradeSubscriber subscriber = new AeronTradeSubscriber(inSub, engine);
            Thread subThread = new Thread(subscriber, "bench-aeron-sub");
            subThread.setDaemon(true);
            subThread.start();

            ExclusivePublication benchPub = driverMgr.aeron().addExclusivePublication(
                "aeron:ipc", config.getAeronInputStreamId());
            Subscription resultSub = driverMgr.aeron().addSubscription(
                "aeron:ipc", config.getAeronOutputStreamId());

            while (!benchPub.isConnected() || !resultSub.isConnected()) {
                Thread.onSpinWait();
            }
            Thread.sleep(100);

            UnsafeBuffer sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
            MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
            TradeExecutionEncoder tradeEncoder = new TradeExecutionEncoder();

            System.out.println("Aeron [" + mode + "]: warming up with " + WARMUP_COUNT + " messages...");
            for (int i = 0; i < WARMUP_COUNT; i++) {
                encodeTrade(sendBuffer, headerEncoder, tradeEncoder, i, System.nanoTime());
                int len = MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength();
                while (benchPub.offer(sendBuffer, 0, len) < 0) {
                    Thread.onSpinWait();
                }
            }

            AtomicLong warmupReceived = new AtomicLong(0);
            IdleStrategy drainIdle = new BusySpinIdleStrategy();
            long drainDeadline = System.nanoTime() + 2_000_000_000L;
            long warmupExpected = (long) WARMUP_COUNT * RESPONSES_PER_TRADE;
            while (warmupReceived.get() < warmupExpected && System.nanoTime() < drainDeadline) {
                int read = resultSub.poll(
                    (buf, off, len, hdr) -> warmupReceived.incrementAndGet(), 256);
                drainIdle.idle(read);
            }

            System.out.println("Aeron [" + mode + "]: measuring " + messageCount + " messages...");
            Histogram histogram = new Histogram(10_000_000_000L, 3);
            int expectedResponses = messageCount * RESPONSES_PER_TRADE;
            CountDownLatch latch = new CountDownLatch(expectedResponses);
            AtomicLong received = new AtomicLong(0);

            MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();
            RatioUpdateDecoder ratioDec = new RatioUpdateDecoder();

            Thread resultThread = new Thread(() -> {
                while (received.get() < expectedResponses) {
                    int read = resultSub.poll((buf, off, len, hdr) -> {
                        long now = System.nanoTime();
                        hdrDec.wrap(buf, off);
                        ratioDec.wrap(buf, off + MessageHeaderDecoder.ENCODED_LENGTH,
                            hdrDec.blockLength(), hdrDec.version());
                        long sendTime = ratioDec.timestamp();
                        long latency = now - sendTime;
                        if (latency > 0) {
                            histogram.recordValue(latency);
                        }
                        received.incrementAndGet();
                        latch.countDown();
                    }, 256);
                    if (read == 0) {
                        Thread.onSpinWait();
                    }
                }
            }, "bench-aeron-result");
            resultThread.setDaemon(true);
            resultThread.start();

            long intervalNanos = targetRps > 0 ? 1_000_000_000L / targetRps : 0;

            long startNanos = System.nanoTime();
            long nextSendTime = startNanos;

            for (int i = 0; i < messageCount; i++) {
                if (intervalNanos > 0) {
                    while (System.nanoTime() < nextSendTime) {
                        Thread.onSpinWait();
                    }
                }

                long sendTime = System.nanoTime();
                encodeTrade(sendBuffer, headerEncoder, tradeEncoder, WARMUP_COUNT + i, sendTime);
                int len = MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength();
                while (benchPub.offer(sendBuffer, 0, len) < 0) {
                    Thread.onSpinWait();
                }

                if (intervalNanos > 0) {
                    nextSendTime += intervalNanos;
                }
            }

            boolean completed = latch.await(30, TimeUnit.SECONDS);
            long elapsedNanos = System.nanoTime() - startNanos;

            if (!completed) {
                System.out.println("Warning: not all responses received. Got " +
                    received.get() + "/" + expectedResponses);
            }

            subscriber.close();
            ratioPublisher.close();

            return new BenchmarkResult("Aeron IPC (" + mode + ")", messageCount, elapsedNanos, histogram);
        } finally {
            driverMgr.close();
        }
    }

    static void encodeTrade(UnsafeBuffer buffer, MessageHeaderEncoder headerEncoder,
                            TradeExecutionEncoder encoder, int seqNo, long timestamp) {
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(seqNo);
        encoder.orderId(seqNo);
        encoder.userId(seqNo % 1000 + 1);
        encoder.side(seqNo % 2 == 0 ? SideEnum.BUY : SideEnum.SELL);
        encoder.price(50000_00L);
        encoder.quantity(100L);
        encoder.timestamp(timestamp);
        encoder.isMaker(seqNo % 3 == 0 ? BooleanType.TRUE : BooleanType.FALSE);
        encoder.symbol("BTC-USDT");
    }

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_MESSAGE_COUNT;
        int targetRps = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        System.out.println("=== Aeron IPC Benchmark: Burst Mode ===");
        BenchmarkResult burstResult = run(count);
        System.out.println(burstResult);
        System.out.println();

        int[] rpsLevels = targetRps > 0
            ? new int[]{targetRps}
            : new int[]{25_000, 50_000, 100_000};

        BenchmarkResult[] pacedResults = new BenchmarkResult[rpsLevels.length];
        for (int r = 0; r < rpsLevels.length; r++) {
            int rps = rpsLevels[r];
            int pacedCount = Math.min(count, rps * 5);
            System.out.println("=== Aeron IPC Benchmark: Paced @ " + rps + " RPS ===");
            pacedResults[r] = run(pacedCount, rps);
            System.out.println(pacedResults[r]);
            System.out.println();
        }

        System.out.println("=== Comparison ===");
        System.out.println(burstResult);
        for (BenchmarkResult pr : pacedResults) {
            System.out.println(pr);
        }
    }
}
