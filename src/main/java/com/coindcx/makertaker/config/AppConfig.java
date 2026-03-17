package com.coindcx.makertaker.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppConfig {
    private String aeronDir;
    private int aeronInputStreamId;
    private int aeronOutputStreamId;

    private String kafkaBootstrapServers;
    private String kafkaInputTopic;
    private String kafkaOutputTopic;
    private String kafkaGroupId;
    private String kafkaAutoOffsetReset;

    private int restPort;

    private AppConfig() {}

    public static AppConfig load() {
        Properties props = new Properties();
        try (InputStream is = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            // fall through to defaults
        }

        AppConfig config = new AppConfig();
        config.aeronDir = env("AERON_DIR", props.getProperty("aeron.dir", defaultAeronDir()));
        config.aeronInputStreamId = Integer.parseInt(
            env("AERON_INPUT_STREAM_ID", props.getProperty("aeron.ipc.input.stream.id", "1001")));
        config.aeronOutputStreamId = Integer.parseInt(
            env("AERON_OUTPUT_STREAM_ID", props.getProperty("aeron.ipc.output.stream.id", "2001")));
        config.kafkaBootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS",
            props.getProperty("kafka.bootstrap.servers", "localhost:9092"));
        config.kafkaInputTopic = env("KAFKA_INPUT_TOPIC",
            props.getProperty("kafka.input.topic", "trade.executions"));
        config.kafkaOutputTopic = env("KAFKA_OUTPUT_TOPIC",
            props.getProperty("kafka.output.topic", "user.ratio.updates"));
        config.kafkaGroupId = env("KAFKA_GROUP_ID",
            props.getProperty("kafka.group.id", "maker-taker-ratio-service"));
        config.kafkaAutoOffsetReset = env("KAFKA_AUTO_OFFSET_RESET",
            props.getProperty("kafka.auto.offset.reset", "latest"));
        config.restPort = Integer.parseInt(
            env("REST_PORT", props.getProperty("rest.port", "8080")));
        return config;
    }

    public static AppConfig defaults() {
        AppConfig config = new AppConfig();
        config.aeronDir = defaultAeronDir();
        config.aeronInputStreamId = 1001;
        config.aeronOutputStreamId = 2001;
        config.kafkaBootstrapServers = "localhost:9092";
        config.kafkaInputTopic = "trade.executions";
        config.kafkaOutputTopic = "user.ratio.updates";
        config.kafkaGroupId = "maker-taker-ratio-service";
        config.kafkaAutoOffsetReset = "latest";
        config.restPort = 8080;
        return config;
    }

    static String defaultAeronDir() {
        String shmDir = "/dev/shm";
        if (new java.io.File(shmDir).isDirectory()) {
            return shmDir + "/aeron-ratio";
        }
        return System.getProperty("java.io.tmpdir") + "/aeron-ratio";
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : System.getProperty(key.toLowerCase().replace('_', '.'), defaultValue);
    }

    public String getAeronDir() { return aeronDir; }
    public int getAeronInputStreamId() { return aeronInputStreamId; }
    public int getAeronOutputStreamId() { return aeronOutputStreamId; }
    public String getKafkaBootstrapServers() { return kafkaBootstrapServers; }
    public String getKafkaInputTopic() { return kafkaInputTopic; }
    public String getKafkaOutputTopic() { return kafkaOutputTopic; }
    public String getKafkaGroupId() { return kafkaGroupId; }
    public String getKafkaAutoOffsetReset() { return kafkaAutoOffsetReset; }
    public int getRestPort() { return restPort; }

    public void setAeronDir(String aeronDir) { this.aeronDir = aeronDir; }
    public void setAeronInputStreamId(int id) { this.aeronInputStreamId = id; }
    public void setAeronOutputStreamId(int id) { this.aeronOutputStreamId = id; }
    public void setKafkaBootstrapServers(String s) { this.kafkaBootstrapServers = s; }
    public void setKafkaInputTopic(String s) { this.kafkaInputTopic = s; }
    public void setKafkaOutputTopic(String s) { this.kafkaOutputTopic = s; }
    public void setKafkaGroupId(String s) { this.kafkaGroupId = s; }
    public void setKafkaAutoOffsetReset(String s) { this.kafkaAutoOffsetReset = s; }
    public void setRestPort(int port) { this.restPort = port; }
}
