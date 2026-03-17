package com.coindcx.makertaker.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void defaultsReturnsNonNullConfig() {
        AppConfig config = AppConfig.defaults();
        assertNotNull(config);
    }

    @Test
    void defaultsAeronDir() {
        AppConfig config = AppConfig.defaults();
        assertEquals(AppConfig.defaultAeronDir(), config.getAeronDir());
    }

    @Test
    void defaultsAeronInputStreamId() {
        AppConfig config = AppConfig.defaults();
        assertEquals(1001, config.getAeronInputStreamId());
    }

    @Test
    void defaultsAeronOutputStreamId() {
        AppConfig config = AppConfig.defaults();
        assertEquals(2001, config.getAeronOutputStreamId());
    }

    @Test
    void defaultsKafkaBootstrapServers() {
        AppConfig config = AppConfig.defaults();
        assertEquals("localhost:9092", config.getKafkaBootstrapServers());
    }

    @Test
    void defaultsKafkaInputTopic() {
        AppConfig config = AppConfig.defaults();
        assertEquals("trade.executions", config.getKafkaInputTopic());
    }

    @Test
    void defaultsKafkaOutputTopic() {
        AppConfig config = AppConfig.defaults();
        assertEquals("user.ratio.updates", config.getKafkaOutputTopic());
    }

    @Test
    void defaultsKafkaGroupId() {
        AppConfig config = AppConfig.defaults();
        assertEquals("maker-taker-ratio-service", config.getKafkaGroupId());
    }

    @Test
    void defaultsKafkaAutoOffsetReset() {
        AppConfig config = AppConfig.defaults();
        assertEquals("latest", config.getKafkaAutoOffsetReset());
    }

    @Test
    void defaultsRestPort() {
        AppConfig config = AppConfig.defaults();
        assertEquals(8080, config.getRestPort());
    }

    @Test
    void loadReturnsNonNullConfig() {
        AppConfig config = AppConfig.load();
        assertNotNull(config);
    }

    @Test
    void loadAeronDirHasValue() {
        AppConfig config = AppConfig.load();
        assertNotNull(config.getAeronDir());
        assertFalse(config.getAeronDir().isEmpty());
    }

    @Test
    void loadAeronStreamIdsArePositive() {
        AppConfig config = AppConfig.load();
        assertTrue(config.getAeronInputStreamId() > 0);
        assertTrue(config.getAeronOutputStreamId() > 0);
    }

    @Test
    void loadKafkaBootstrapServersHasValue() {
        AppConfig config = AppConfig.load();
        assertNotNull(config.getKafkaBootstrapServers());
        assertFalse(config.getKafkaBootstrapServers().isEmpty());
    }

    @Test
    void loadKafkaTopicsHaveValues() {
        AppConfig config = AppConfig.load();
        assertNotNull(config.getKafkaInputTopic());
        assertFalse(config.getKafkaInputTopic().isEmpty());
        assertNotNull(config.getKafkaOutputTopic());
        assertFalse(config.getKafkaOutputTopic().isEmpty());
    }

    @Test
    void loadKafkaGroupIdHasValue() {
        AppConfig config = AppConfig.load();
        assertNotNull(config.getKafkaGroupId());
        assertFalse(config.getKafkaGroupId().isEmpty());
    }

    @Test
    void loadKafkaAutoOffsetResetHasValue() {
        AppConfig config = AppConfig.load();
        assertNotNull(config.getKafkaAutoOffsetReset());
        assertFalse(config.getKafkaAutoOffsetReset().isEmpty());
    }

    @Test
    void loadRestPortIsPositive() {
        AppConfig config = AppConfig.load();
        assertTrue(config.getRestPort() > 0);
    }

    @Test
    void settersUpdateAeronDir() {
        AppConfig config = AppConfig.defaults();
        config.setAeronDir("/tmp/aeron-test");
        assertEquals("/tmp/aeron-test", config.getAeronDir());
    }

    @Test
    void settersUpdateAeronInputStreamId() {
        AppConfig config = AppConfig.defaults();
        config.setAeronInputStreamId(5555);
        assertEquals(5555, config.getAeronInputStreamId());
    }

    @Test
    void settersUpdateAeronOutputStreamId() {
        AppConfig config = AppConfig.defaults();
        config.setAeronOutputStreamId(6666);
        assertEquals(6666, config.getAeronOutputStreamId());
    }

    @Test
    void settersUpdateKafkaBootstrapServers() {
        AppConfig config = AppConfig.defaults();
        config.setKafkaBootstrapServers("broker:9093");
        assertEquals("broker:9093", config.getKafkaBootstrapServers());
    }

    @Test
    void settersUpdateKafkaInputTopic() {
        AppConfig config = AppConfig.defaults();
        config.setKafkaInputTopic("custom.input");
        assertEquals("custom.input", config.getKafkaInputTopic());
    }

    @Test
    void settersUpdateKafkaOutputTopic() {
        AppConfig config = AppConfig.defaults();
        config.setKafkaOutputTopic("custom.output");
        assertEquals("custom.output", config.getKafkaOutputTopic());
    }

    @Test
    void settersUpdateKafkaGroupId() {
        AppConfig config = AppConfig.defaults();
        config.setKafkaGroupId("custom-group");
        assertEquals("custom-group", config.getKafkaGroupId());
    }

    @Test
    void settersUpdateKafkaAutoOffsetReset() {
        AppConfig config = AppConfig.defaults();
        config.setKafkaAutoOffsetReset("earliest");
        assertEquals("earliest", config.getKafkaAutoOffsetReset());
    }

    @Test
    void settersUpdateRestPort() {
        AppConfig config = AppConfig.defaults();
        config.setRestPort(9090);
        assertEquals(9090, config.getRestPort());
    }

    @Test
    void defaultsAndLoadProduceSameDefaults() {
        AppConfig defaults = AppConfig.defaults();
        AppConfig loaded = AppConfig.load();

        assertEquals(defaults.getAeronDir(), loaded.getAeronDir());
        assertEquals(defaults.getAeronInputStreamId(), loaded.getAeronInputStreamId());
        assertEquals(defaults.getAeronOutputStreamId(), loaded.getAeronOutputStreamId());
        assertEquals(defaults.getKafkaBootstrapServers(), loaded.getKafkaBootstrapServers());
        assertEquals(defaults.getKafkaInputTopic(), loaded.getKafkaInputTopic());
        assertEquals(defaults.getKafkaOutputTopic(), loaded.getKafkaOutputTopic());
        assertEquals(defaults.getKafkaGroupId(), loaded.getKafkaGroupId());
        assertEquals(defaults.getKafkaAutoOffsetReset(), loaded.getKafkaAutoOffsetReset());
        assertEquals(defaults.getRestPort(), loaded.getRestPort());
    }

    @Test
    void loadCalledMultipleTimesReturnsDistinctInstances() {
        AppConfig first = AppConfig.load();
        AppConfig second = AppConfig.load();

        assertNotSame(first, second);
        assertEquals(first.getAeronDir(), second.getAeronDir());
    }

    @Test
    void defaultsCalledMultipleTimesReturnsDistinctInstances() {
        AppConfig first = AppConfig.defaults();
        AppConfig second = AppConfig.defaults();

        assertNotSame(first, second);
    }

    @Test
    void setRestPortToZero() {
        AppConfig config = AppConfig.defaults();
        config.setRestPort(0);
        assertEquals(0, config.getRestPort());
    }

    @Test
    void setAeronStreamIdToZero() {
        AppConfig config = AppConfig.defaults();
        config.setAeronInputStreamId(0);
        config.setAeronOutputStreamId(0);
        assertEquals(0, config.getAeronInputStreamId());
        assertEquals(0, config.getAeronOutputStreamId());
    }
}
