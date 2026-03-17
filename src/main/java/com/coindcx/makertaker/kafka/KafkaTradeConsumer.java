package com.coindcx.makertaker.kafka;

import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.sbe.BooleanType;
import com.coindcx.makertaker.sbe.MessageHeaderDecoder;
import com.coindcx.makertaker.sbe.TradeExecutionDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public final class KafkaTradeConsumer implements Runnable, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KafkaTradeConsumer.class);

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final RatioEngine engine;
    private final String topic;
    private volatile boolean running = true;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final TradeExecutionDecoder tradeDecoder = new TradeExecutionDecoder();
    private final UnsafeBuffer decodeBuffer = new UnsafeBuffer(new byte[0]);

    public KafkaTradeConsumer(AppConfig config, RatioEngine engine) {
        this(createConsumer(config), config.getKafkaInputTopic(), engine);
    }

    public KafkaTradeConsumer(KafkaConsumer<byte[], byte[]> consumer, String topic, RatioEngine engine) {
        this.consumer = consumer;
        this.topic = topic;
        this.engine = engine;
    }

    static KafkaConsumer<byte[], byte[]> createConsumer(AppConfig config) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getKafkaGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.getKafkaAutoOffsetReset());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        return new KafkaConsumer<>(props);
    }

    @Override
    public void run() {
        log.info("Kafka trade consumer started on topic: {}", topic);
        consumer.subscribe(Collections.singletonList(topic));

        try {
            while (running) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    processRecord(record);
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } finally {
            consumer.close();
            log.info("Kafka trade consumer stopped");
        }
    }

    void processRecord(ConsumerRecord<byte[], byte[]> record) {
        byte[] value = record.value();
        if (value == null || value.length == 0) {
            return;
        }

        try {
            decodeBuffer.wrap(value);
            headerDecoder.wrap(decodeBuffer, 0);

            if (headerDecoder.templateId() != TradeExecutionDecoder.TEMPLATE_ID) {
                log.warn("Unknown template ID from Kafka: {}", headerDecoder.templateId());
                return;
            }

            tradeDecoder.wrap(
                decodeBuffer,
                MessageHeaderDecoder.ENCODED_LENGTH,
                headerDecoder.blockLength(),
                headerDecoder.version()
            );

            engine.process(
                tradeDecoder.executionId(),
                tradeDecoder.orderId(),
                tradeDecoder.userId(),
                tradeDecoder.symbol(),
                tradeDecoder.side().value(),
                tradeDecoder.price(),
                tradeDecoder.quantity(),
                tradeDecoder.timestamp(),
                tradeDecoder.isMaker() == BooleanType.TRUE
            );
        } catch (Exception e) {
            log.error("Failed to decode trade execution from Kafka: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        running = false;
        consumer.wakeup();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }
}
