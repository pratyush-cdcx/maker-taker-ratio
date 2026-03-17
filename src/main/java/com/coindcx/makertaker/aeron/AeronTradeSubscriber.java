package com.coindcx.makertaker.aeron;

import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.sbe.BooleanType;
import com.coindcx.makertaker.sbe.MessageHeaderDecoder;
import com.coindcx.makertaker.sbe.TradeExecutionDecoder;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AeronTradeSubscriber implements Runnable, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronTradeSubscriber.class);
    private static final int FRAGMENT_LIMIT = 256;

    private final Subscription subscription;
    private final RatioEngine engine;
    private final IdleStrategy idleStrategy;
    private volatile boolean running = true;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final TradeExecutionDecoder tradeDecoder = new TradeExecutionDecoder();

    public AeronTradeSubscriber(Subscription subscription, RatioEngine engine) {
        this(subscription, engine, new BusySpinIdleStrategy());
    }

    public AeronTradeSubscriber(Subscription subscription, RatioEngine engine, IdleStrategy idleStrategy) {
        this.subscription = subscription;
        this.engine = engine;
        this.idleStrategy = idleStrategy;
    }

    @Override
    public void run() {
        log.info("Aeron trade subscriber started on stream {}", subscription.streamId());
        final FragmentHandler handler = this::onFragment;

        while (running) {
            int fragmentsRead = subscription.poll(handler, FRAGMENT_LIMIT);
            idleStrategy.idle(fragmentsRead);
        }
        log.info("Aeron trade subscriber stopped");
    }

    @SuppressWarnings("unused")
    void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != TradeExecutionDecoder.TEMPLATE_ID) {
            log.warn("Unknown template ID: {}", headerDecoder.templateId());
            return;
        }

        tradeDecoder.wrap(
            buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
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
    }

    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        stop();
        if (subscription != null && !subscription.isClosed()) {
            subscription.close();
        }
    }

    public boolean isRunning() {
        return running;
    }
}
