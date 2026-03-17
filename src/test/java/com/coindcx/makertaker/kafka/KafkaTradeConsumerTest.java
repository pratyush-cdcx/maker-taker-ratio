package com.coindcx.makertaker.kafka;

import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaTradeConsumerTest {

    private static final String TOPIC = "trade-executions";

    @Mock
    private KafkaConsumer<byte[], byte[]> kafkaConsumer;

    @Mock
    private RatioEngine engine;

    private KafkaTradeConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaTradeConsumer(kafkaConsumer, TOPIC, engine);
    }

    private byte[] encodeTrade(long executionId, long orderId, long userId,
                               String symbol, SideEnum side, long price,
                               long quantity, long timestamp, BooleanType isMaker) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(512));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(executionId);
        encoder.orderId(orderId);
        encoder.userId(userId);
        encoder.side(side);
        encoder.price(price);
        encoder.quantity(quantity);
        encoder.timestamp(timestamp);
        encoder.isMaker(isMaker);
        encoder.symbol(symbol);

        int length = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
        byte[] result = new byte[length];
        buffer.getBytes(0, result);
        return result;
    }

    @Test
    void processRecordDecodesAndCallsEngine() {
        byte[] value = encodeTrade(100L, 200L, 42L, "BTCUSDT",
            SideEnum.BUY, 50000L, 1000L, 1700000000L, BooleanType.TRUE);

        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, value);
        consumer.processRecord(record);

        verify(engine).process(
            eq(100L), eq(200L), eq(42L), eq("BTCUSDT"),
            eq((int) SideEnum.BUY.value()),
            eq(50000L), eq(1000L), eq(1700000000L), eq(true)
        );
    }

    @Test
    void processRecordWithSellSideAndTaker() {
        byte[] value = encodeTrade(101L, 201L, 43L, "ETHUSDT",
            SideEnum.SELL, 3000L, 500L, 1700000001L, BooleanType.FALSE);

        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, value);
        consumer.processRecord(record);

        verify(engine).process(
            eq(101L), eq(201L), eq(43L), eq("ETHUSDT"),
            eq((int) SideEnum.SELL.value()),
            eq(3000L), eq(500L), eq(1700000001L), eq(false)
        );
    }

    @Test
    void processRecordIgnoresNullValue() {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, null);
        consumer.processRecord(record);

        verifyNoInteractions(engine);
    }

    @Test
    void processRecordIgnoresEmptyValue() {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, new byte[0]);
        consumer.processRecord(record);

        verifyNoInteractions(engine);
    }

    @Test
    void processRecordIgnoresUnknownTemplateId() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(128));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        headerEncoder.wrap(buffer, 0)
            .blockLength(50)
            .templateId(999)
            .schemaId(1)
            .version(1);

        byte[] value = new byte[64];
        buffer.getBytes(0, value);

        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, value);
        consumer.processRecord(record);

        verifyNoInteractions(engine);
    }

    @Test
    void processRecordHandlesCorruptedData() {
        byte[] corrupted = new byte[]{0, 0, 1, 0, 1, 0, 1, 0};
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, corrupted);

        assertDoesNotThrow(() -> consumer.processRecord(record));
    }

    @Test
    void stopSetsRunningFalseAndWakesConsumer() {
        assertTrue(consumer.isRunning());

        consumer.stop();

        assertFalse(consumer.isRunning());
        verify(kafkaConsumer).wakeup();
    }

    @Test
    void closeCallsStop() {
        consumer.close();

        assertFalse(consumer.isRunning());
        verify(kafkaConsumer).wakeup();
    }

    @Test
    void isRunningDefaultsToTrue() {
        assertTrue(consumer.isRunning());
    }

    @Test
    void runSubscribesAndPollsUntilStopped() {
        byte[] tradeBytes = encodeTrade(1L, 2L, 3L, "BTCUSDT",
            SideEnum.BUY, 100L, 10L, 5000L, BooleanType.TRUE);
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(TOPIC, 0, 0, null, tradeBytes);
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> map = new HashMap<>();
        map.put(tp, Collections.singletonList(record));
        ConsumerRecords<byte[], byte[]> records = new ConsumerRecords<>(map);
        ConsumerRecords<byte[], byte[]> empty = new ConsumerRecords<>(Collections.emptyMap());

        AtomicInteger pollCount = new AtomicInteger();
        when(kafkaConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            int count = pollCount.incrementAndGet();
            if (count == 1) return records;
            consumer.stop();
            return empty;
        });

        doThrow(new WakeupException()).when(kafkaConsumer).poll(any(Duration.class));
        reset(kafkaConsumer);

        pollCount.set(0);
        when(kafkaConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            int count = pollCount.incrementAndGet();
            if (count == 1) return records;
            consumer.stop();
            return empty;
        });

        consumer.run();

        assertFalse(consumer.isRunning());
        verify(kafkaConsumer).subscribe(Collections.singletonList(TOPIC));
        verify(kafkaConsumer).close();
        verify(engine).process(eq(1L), eq(2L), eq(3L), eq("BTCUSDT"),
            eq((int) SideEnum.BUY.value()), eq(100L), eq(10L), eq(5000L), eq(true));
    }

    @Test
    void runHandlesWakeupExceptionWhenNotRunning() {
        when(kafkaConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            consumer.stop();
            throw new WakeupException();
        });

        assertDoesNotThrow(() -> consumer.run());
        verify(kafkaConsumer).close();
    }

    @Test
    void runRethrowsWakeupExceptionWhenStillRunning() {
        when(kafkaConsumer.poll(any(Duration.class))).thenThrow(new WakeupException());

        assertThrows(WakeupException.class, () -> consumer.run());
        verify(kafkaConsumer).close();
    }

    @Test
    void createConsumerReturnsNonNull() {
        AppConfig config = AppConfig.defaults();
        KafkaConsumer<byte[], byte[]> created = KafkaTradeConsumer.createConsumer(config);
        assertNotNull(created);
        created.close();
    }
}
