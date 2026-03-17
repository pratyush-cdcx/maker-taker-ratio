package com.coindcx.makertaker.kafka;

import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.model.UserSymbolState.WindowSnapshot;
import com.coindcx.makertaker.sbe.MessageHeaderDecoder;
import com.coindcx.makertaker.sbe.RatioUpdateDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaRatioProducerTest {

    private static final String TOPIC = "ratio-updates";

    @Mock
    private KafkaProducer<byte[], byte[]> kafkaProducer;

    @Captor
    private ArgumentCaptor<ProducerRecord<byte[], byte[]>> recordCaptor;

    @Captor
    private ArgumentCaptor<Callback> callbackCaptor;

    private KafkaRatioProducer producer;

    @BeforeEach
    void setUp() {
        producer = new KafkaRatioProducer(kafkaProducer, TOPIC);
    }

    private WindowSnapshot createSnapshot(long userId, String symbol) {
        return new WindowSnapshot(
            userId, symbol,
            new long[]{10, 20, 30, 40},
            new long[]{5, 15, 25, 35},
            new double[]{0.5, 0.6, 0.7, 0.8},
            1700000000L
        );
    }

    @Test
    void publishSendsRecordWithCorrectTopic() {
        producer.publish(createSnapshot(42L, "BTCUSDT"));

        verify(kafkaProducer).send(recordCaptor.capture(), any(Callback.class));
        assertEquals(TOPIC, recordCaptor.getValue().topic());
    }

    @Test
    void publishSendsKeyAsUserId() {
        producer.publish(createSnapshot(42L, "BTCUSDT"));

        verify(kafkaProducer).send(recordCaptor.capture(), any(Callback.class));
        assertArrayEquals("42".getBytes(), recordCaptor.getValue().key());
    }

    @Test
    void publishEncodesValidSbePayload() {
        producer.publish(createSnapshot(42L, "BTCUSDT"));

        verify(kafkaProducer).send(recordCaptor.capture(), any(Callback.class));
        byte[] value = recordCaptor.getValue().value();
        assertNotNull(value);
        assertTrue(value.length > 0);

        UnsafeBuffer decodeBuffer = new UnsafeBuffer(value);
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        headerDecoder.wrap(decodeBuffer, 0);

        assertEquals(RatioUpdateDecoder.TEMPLATE_ID, headerDecoder.templateId());
        assertEquals(RatioUpdateDecoder.BLOCK_LENGTH, headerDecoder.blockLength());

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrap(decodeBuffer, MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(), headerDecoder.version());

        assertEquals(42L, decoder.userId());
        assertEquals(1700000000L, decoder.timestamp());
        assertEquals(10L, decoder.makerCount1H());
        assertEquals(5L, decoder.takerCount1H());
        assertEquals(0.5, decoder.ratio1H(), 0.001);
        assertEquals(20L, decoder.makerCount24H());
        assertEquals(15L, decoder.takerCount24H());
        assertEquals(0.6, decoder.ratio24H(), 0.001);
        assertEquals(30L, decoder.makerCount7D());
        assertEquals(25L, decoder.takerCount7D());
        assertEquals(0.7, decoder.ratio7D(), 0.001);
        assertEquals(40L, decoder.makerCount30D());
        assertEquals(35L, decoder.takerCount30D());
        assertEquals(0.8, decoder.ratio30D(), 0.001);
        assertEquals("BTCUSDT", decoder.symbol());
    }

    @Test
    void publishWithNullSymbolEncodesEmptyString() {
        producer.publish(createSnapshot(1L, null));

        verify(kafkaProducer).send(recordCaptor.capture(), any(Callback.class));
        byte[] value = recordCaptor.getValue().value();
        UnsafeBuffer decodeBuffer = new UnsafeBuffer(value);
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        headerDecoder.wrap(decodeBuffer, 0);
        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrap(decodeBuffer, MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(), headerDecoder.version());

        assertEquals("", decoder.symbol());
    }

    @Test
    void callbackLogsOnException() {
        producer.publish(createSnapshot(42L, "BTCUSDT"));

        verify(kafkaProducer).send(any(), callbackCaptor.capture());
        Callback callback = callbackCaptor.getValue();
        assertDoesNotThrow(() -> callback.onCompletion(null, new RuntimeException("send failed")));
    }

    @Test
    void callbackNoExceptionIsNoop() {
        producer.publish(createSnapshot(42L, "BTCUSDT"));

        verify(kafkaProducer).send(any(), callbackCaptor.capture());
        Callback callback = callbackCaptor.getValue();
        assertDoesNotThrow(() -> callback.onCompletion(null, null));
    }

    @Test
    void closeFlushesAndClosesProducer() {
        producer.close();

        verify(kafkaProducer).flush();
        verify(kafkaProducer).close();
    }

    @Test
    void createProducerReturnsNonNull() {
        AppConfig config = AppConfig.defaults();
        KafkaProducer<byte[], byte[]> created = KafkaRatioProducer.createProducer(config);
        assertNotNull(created);
        created.close();
    }

    @Test
    void constructorWithConfigCreatesProducer() {
        AppConfig config = AppConfig.defaults();
        KafkaRatioProducer p = new KafkaRatioProducer(config);
        assertNotNull(p);
        p.close();
    }
}
