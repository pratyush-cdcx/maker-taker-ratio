package com.coindcx.makertaker.kafka;

import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.model.TimeWindow;
import com.coindcx.makertaker.model.UserSymbolState.WindowSnapshot;
import com.coindcx.makertaker.sbe.MessageHeaderEncoder;
import com.coindcx.makertaker.sbe.RatioUpdateEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Properties;

public final class KafkaRatioProducer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KafkaRatioProducer.class);
    private static final int BUFFER_SIZE = 512;

    private final KafkaProducer<byte[], byte[]> producer;
    private final String topic;
    private final UnsafeBuffer encodeBuffer;
    private final MessageHeaderEncoder headerEncoder;
    private final RatioUpdateEncoder encoder;

    public KafkaRatioProducer(AppConfig config) {
        this(createProducer(config), config.getKafkaOutputTopic());
    }

    public KafkaRatioProducer(KafkaProducer<byte[], byte[]> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
        this.encodeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
        this.headerEncoder = new MessageHeaderEncoder();
        this.encoder = new RatioUpdateEncoder();
    }

    static KafkaProducer<byte[], byte[]> createProducer(AppConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "1");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        return new KafkaProducer<>(props);
    }

    public void publish(WindowSnapshot snapshot) {
        encoder.wrapAndApplyHeader(encodeBuffer, 0, headerEncoder);

        int h1 = TimeWindow.HOUR_1.ordinal();
        int h24 = TimeWindow.HOUR_24.ordinal();
        int d7 = TimeWindow.DAY_7.ordinal();
        int d30 = TimeWindow.DAY_30.ordinal();

        encoder.userId(snapshot.userId());
        encoder.timestamp(snapshot.lastUpdatedTimestamp());
        encoder.makerCount1H(snapshot.makerCounts()[h1]);
        encoder.takerCount1H(snapshot.takerCounts()[h1]);
        encoder.ratio1H(snapshot.ratios()[h1]);
        encoder.makerCount24H(snapshot.makerCounts()[h24]);
        encoder.takerCount24H(snapshot.takerCounts()[h24]);
        encoder.ratio24H(snapshot.ratios()[h24]);
        encoder.makerCount7D(snapshot.makerCounts()[d7]);
        encoder.takerCount7D(snapshot.takerCounts()[d7]);
        encoder.ratio7D(snapshot.ratios()[d7]);
        encoder.makerCount30D(snapshot.makerCounts()[d30]);
        encoder.takerCount30D(snapshot.takerCounts()[d30]);
        encoder.ratio30D(snapshot.ratios()[d30]);

        String symbol = snapshot.symbol() != null ? snapshot.symbol() : "";
        encoder.symbol(symbol);

        int length = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
        byte[] value = new byte[length];
        encodeBuffer.getBytes(0, value);

        byte[] key = Long.toString(snapshot.userId()).getBytes();
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key, value);

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.warn("Failed to send ratio update to Kafka: {}", exception.getMessage());
            }
        });
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}
