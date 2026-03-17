package com.coindcx.makertaker;

import com.coindcx.makertaker.aeron.AeronMediaDriverManager;
import com.coindcx.makertaker.aeron.AeronRatioPublisher;
import com.coindcx.makertaker.aeron.AeronTradeSubscriber;
import com.coindcx.makertaker.config.AppConfig;
import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.kafka.KafkaRatioProducer;
import com.coindcx.makertaker.kafka.KafkaTradeConsumer;
import com.coindcx.makertaker.rest.RatioRestServer;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Maker/Taker Ratio Service");
        AppConfig config = AppConfig.load();

        boolean aeronEnabled = !"false".equalsIgnoreCase(System.getenv("AERON_ENABLED"));
        boolean kafkaEnabled = !"false".equalsIgnoreCase(System.getenv("KAFKA_ENABLED"));

        AeronMediaDriverManager driverMgr = null;
        AeronRatioPublisher aeronPub = null;
        AeronTradeSubscriber aeronSub = null;
        KafkaRatioProducer kafkaProd = null;
        KafkaTradeConsumer kafkaCon = null;

        InMemoryRatioStore store = new InMemoryRatioStore();

        if (aeronEnabled) {
            driverMgr = new AeronMediaDriverManager(config);

            ExclusivePublication publication = driverMgr.aeron().addExclusivePublication(
                "aeron:ipc", config.getAeronOutputStreamId());
            aeronPub = new AeronRatioPublisher(publication);
        }

        if (kafkaEnabled) {
            kafkaProd = new KafkaRatioProducer(config);
        }

        RatioEngine engine = new RatioEngine(store, aeronPub, kafkaProd);

        if (aeronEnabled) {
            Subscription subscription = driverMgr.aeron().addSubscription(
                "aeron:ipc", config.getAeronInputStreamId());
            aeronSub = new AeronTradeSubscriber(subscription, engine);
        }

        if (kafkaEnabled) {
            kafkaCon = new KafkaTradeConsumer(config, engine);
        }

        RatioRestServer restServer = new RatioRestServer(config.getRestPort(), store);
        restServer.start();

        Thread aeronThread = null;
        if (aeronSub != null) {
            aeronThread = new Thread(aeronSub, "aeron-subscriber");
            aeronThread.setDaemon(true);
            aeronThread.start();
            log.info("Aeron subscriber thread started");
        }

        Thread kafkaThread = null;
        if (kafkaCon != null) {
            kafkaThread = new Thread(kafkaCon, "kafka-consumer");
            kafkaThread.setDaemon(true);
            kafkaThread.start();
            log.info("Kafka consumer thread started");
        }

        final AeronTradeSubscriber fAeronSub = aeronSub;
        final KafkaTradeConsumer fKafkaCon = kafkaCon;
        final KafkaRatioProducer fKafkaProd = kafkaProd;
        final AeronRatioPublisher fAeronPub = aeronPub;
        final AeronMediaDriverManager fDriverMgr = driverMgr;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Maker/Taker Ratio Service");
            if (fAeronSub != null) fAeronSub.close();
            if (fKafkaCon != null) fKafkaCon.close();
            restServer.close();
            if (fKafkaProd != null) fKafkaProd.close();
            if (fAeronPub != null) fAeronPub.close();
            if (fDriverMgr != null) fDriverMgr.close();
            log.info("Shutdown complete");
        }, "shutdown-hook"));

        log.info("Maker/Taker Ratio Service started successfully");
        log.info("REST API: http://localhost:{}/api/v1/users/{{userId}}/ratio", config.getRestPort());

        Thread.currentThread().join();
    }
}
