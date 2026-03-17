package com.coindcx.makertaker.engine;

import com.coindcx.makertaker.aeron.AeronRatioPublisher;
import com.coindcx.makertaker.kafka.KafkaRatioProducer;
import com.coindcx.makertaker.model.UserSymbolState;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RatioEngine {
    private static final Logger log = LoggerFactory.getLogger(RatioEngine.class);

    private final InMemoryRatioStore store;
    private final AeronRatioPublisher aeronPublisher;
    private final KafkaRatioProducer kafkaProducer;

    public RatioEngine(InMemoryRatioStore store,
                       AeronRatioPublisher aeronPublisher,
                       KafkaRatioProducer kafkaProducer) {
        this.store = store;
        this.aeronPublisher = aeronPublisher;
        this.kafkaProducer = kafkaProducer;
    }

    public void process(long executionId, long orderId, long userId,
                        String symbol, int side, long price, long quantity,
                        long timestamp, boolean isMaker) {
        UserSymbolState overall = store.getOrCreateOverall(userId);
        overall.record(timestamp, isMaker);

        UserSymbolState perSymbol = store.getOrCreatePerSymbol(userId, symbol);
        perSymbol.record(timestamp, isMaker);

        publishRatio(overall);
        publishRatio(perSymbol);
    }

    private void publishRatio(UserSymbolState state) {
        if (aeronPublisher != null) {
            try {
                aeronPublisher.publishDirect(state);
            } catch (Exception e) {
                log.warn("Failed to publish ratio update to Aeron: {}", e.getMessage());
            }
        }
        if (kafkaProducer != null) {
            try {
                kafkaProducer.publish(state.snapshot());
            } catch (Exception e) {
                log.warn("Failed to publish ratio update to Kafka: {}", e.getMessage());
            }
        }
    }

    public InMemoryRatioStore getStore() {
        return store;
    }
}
