package com.coindcx.makertaker.benchmark;

import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.kafka.KafkaRatioProducer;
import com.coindcx.makertaker.kafka.KafkaTradeConsumer;
import com.coindcx.makertaker.sbe.BooleanType;
import com.coindcx.makertaker.sbe.MessageHeaderDecoder;
import com.coindcx.makertaker.sbe.MessageHeaderEncoder;
import com.coindcx.makertaker.sbe.RatioUpdateDecoder;
import com.coindcx.makertaker.sbe.SideEnum;
import com.coindcx.makertaker.sbe.TradeExecutionEncoder;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.HdrHistogram.Histogram;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class KafkaBenchmark {
    private static final int WARMUP_COUNT = 10_000;
    private static final int DEFAULT_MESSAGE_COUNT = 100_000;
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
        String inputTopic = config.getKafkaInputTopic();
        String outputTopic = config.getKafkaOutputTopic();

        InMemoryRatioStore store = new InMemoryRatioStore();
        KafkaRatioProducer ratioProducer = new KafkaRatioProducer(config);
        RatioEngine engine = new RatioEngine(store, null, ratioProducer);

        KafkaTradeConsumer tradeConsumer = new KafkaTradeConsumer(config, engine);
        Thread consumerThread = new Thread(tradeConsumer, "bench-kafka-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        KafkaProducer<byte[], byte[]> benchProducer = createProducer(config);
        KafkaConsumer<byte[], byte[]> resultConsumer = createResultConsumer(config, outputTopic);

        UnsafeBuffer sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder tradeEncoder = new TradeExecutionEncoder();

        System.out.println("Kafka [" + mode + "]: warming up with " + WARMUP_COUNT + " messages...");
        for (int i = 0; i < WARMUP_COUNT; i++) {
            encodeTrade(sendBuffer, headerEncoder, tradeEncoder, i, System.nanoTime());
            int len = MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength();
            byte[] value = new byte[len];
            sendBuffer.getBytes(0, value);
            benchProducer.send(new ProducerRecord<>(inputTopic, value));
        }
        benchProducer.flush();

        long drainDeadline = System.currentTimeMillis() + 10_000;
        long warmupReceived = 0;
        long warmupExpected = (long) WARMUP_COUNT * RESPONSES_PER_TRADE;
        while (warmupReceived < warmupExpected && System.currentTimeMillis() < drainDeadline) {
            ConsumerRecords<byte[], byte[]> records = resultConsumer.poll(Duration.ofMillis(100));
            warmupReceived += records.count();
        }

        System.out.println("Kafka [" + mode + "]: measuring " + messageCount + " messages...");
        Histogram histogram = new Histogram(10_000_000_000L, 3);
        int expectedResponses = messageCount * RESPONSES_PER_TRADE;
        CountDownLatch latch = new CountDownLatch(expectedResponses);
        AtomicLong received = new AtomicLong(0);

        Thread resultThread = new Thread(() -> {
            MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();
            RatioUpdateDecoder ratioDec = new RatioUpdateDecoder();
            UnsafeBuffer decodeBuffer = new UnsafeBuffer(new byte[0]);

            while (received.get() < expectedResponses) {
                ConsumerRecords<byte[], byte[]> records = resultConsumer.poll(Duration.ofMillis(10));
                records.forEach(record -> {
                    long receiveTime = System.nanoTime();
                    decodeBuffer.wrap(record.value());
                    hdrDec.wrap(decodeBuffer, 0);
                    ratioDec.wrap(decodeBuffer, MessageHeaderDecoder.ENCODED_LENGTH,
                        hdrDec.blockLength(), hdrDec.version());
                    long sendTimeRead = ratioDec.timestamp();
                    long latency = receiveTime - sendTimeRead;
                    if (latency > 0) {
                        histogram.recordValue(latency);
                    }
                    received.incrementAndGet();
                    latch.countDown();
                });
            }
        }, "bench-kafka-result");
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
            byte[] value = new byte[len];
            sendBuffer.getBytes(0, value);
            benchProducer.send(new ProducerRecord<>(inputTopic, value));

            if (intervalNanos > 0) {
                nextSendTime += intervalNanos;
            }
        }
        benchProducer.flush();

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - startNanos;

        if (!completed) {
            System.out.println("Warning: not all responses received. Got " +
                received.get() + "/" + expectedResponses);
        }

        tradeConsumer.close();
        ratioProducer.close();
        benchProducer.close();
        resultConsumer.close();

        return new BenchmarkResult("Kafka (" + mode + ")", messageCount, elapsedNanos, histogram);
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

    private static KafkaProducer<byte[], byte[]> createProducer(AppConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<byte[], byte[]> createResultConsumer(AppConfig config, String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "benchmark-consumer-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1");
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "10");
        KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        consumer.poll(Duration.ofMillis(100));
        return consumer;
    }

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_MESSAGE_COUNT;
        int targetRps = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        System.out.println("=== Kafka Benchmark: Burst Mode ===");
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
            System.out.println("=== Kafka Benchmark: Paced @ " + rps + " RPS ===");
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
