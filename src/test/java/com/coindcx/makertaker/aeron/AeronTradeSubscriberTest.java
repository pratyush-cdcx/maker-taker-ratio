package com.coindcx.makertaker.aeron;

import com.coindcx.makertaker.engine.RatioEngine;
import com.coindcx.makertaker.sbe.*;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AeronTradeSubscriberTest {

    @Mock
    private Subscription subscription;

    @Mock
    private RatioEngine engine;

    @Mock
    private Header header;

    private AeronTradeSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new AeronTradeSubscriber(subscription, engine);
    }

    private UnsafeBuffer encodeTrade(long executionId, long orderId, long userId,
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

        return buffer;
    }

    @Test
    void onFragmentDecodesAndCallsEngine() {
        UnsafeBuffer buffer = encodeTrade(
            100L, 200L, 42L, "BTCUSDT", SideEnum.BUY,
            50000L, 1000L, 1700000000L, BooleanType.TRUE
        );

        int length = MessageHeaderEncoder.ENCODED_LENGTH + TradeExecutionEncoder.BLOCK_LENGTH + 4 + "BTCUSDT".length();
        subscriber.onFragment(buffer, 0, length, header);

        verify(engine).process(
            eq(100L), eq(200L), eq(42L), eq("BTCUSDT"),
            eq((int) SideEnum.BUY.value()),
            eq(50000L), eq(1000L), eq(1700000000L), eq(true)
        );
    }

    @Test
    void onFragmentWithSellSideAndTakerFlag() {
        UnsafeBuffer buffer = encodeTrade(
            101L, 201L, 43L, "ETHUSDT", SideEnum.SELL,
            3000L, 500L, 1700000001L, BooleanType.FALSE
        );

        int length = MessageHeaderEncoder.ENCODED_LENGTH + TradeExecutionEncoder.BLOCK_LENGTH + 4 + "ETHUSDT".length();
        subscriber.onFragment(buffer, 0, length, header);

        verify(engine).process(
            eq(101L), eq(201L), eq(43L), eq("ETHUSDT"),
            eq((int) SideEnum.SELL.value()),
            eq(3000L), eq(500L), eq(1700000001L), eq(false)
        );
    }

    @Test
    void onFragmentIgnoresUnknownTemplateId() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(512));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        headerEncoder.wrap(buffer, 0)
            .blockLength(50)
            .templateId(999)
            .schemaId(1)
            .version(1);

        subscriber.onFragment(buffer, 0, 100, header);

        verifyNoInteractions(engine);
    }

    @Test
    void stopSetsRunningToFalse() {
        assertTrue(subscriber.isRunning());

        subscriber.stop();

        assertFalse(subscriber.isRunning());
    }

    @Test
    void closeStopsAndClosesSubscription() {
        when(subscription.isClosed()).thenReturn(false);

        subscriber.close();

        assertFalse(subscriber.isRunning());
        verify(subscription).close();
    }

    @Test
    void closeSkipsIfSubscriptionAlreadyClosed() {
        when(subscription.isClosed()).thenReturn(true);

        subscriber.close();

        assertFalse(subscriber.isRunning());
        verify(subscription, never()).close();
    }

    @Test
    void isRunningDefaultsToTrue() {
        assertTrue(subscriber.isRunning());
    }

    @Test
    void constructorWithCustomIdleStrategy() {
        var idleStrategy = new org.agrona.concurrent.BusySpinIdleStrategy();
        var sub = new AeronTradeSubscriber(subscription, engine, idleStrategy);
        assertTrue(sub.isRunning());
    }

    @Test
    void runPollsAndStopsWhenRunningSetToFalse() {
        IdleStrategy idleStrategy = mock(IdleStrategy.class);
        AeronTradeSubscriber sub = new AeronTradeSubscriber(subscription, engine, idleStrategy);
        when(subscription.streamId()).thenReturn(1);
        when(subscription.poll(any(FragmentHandler.class), eq(256)))
            .thenAnswer(invocation -> {
                sub.stop();
                return 0;
            });

        sub.run();

        assertFalse(sub.isRunning());
        verify(subscription).poll(any(FragmentHandler.class), eq(256));
        verify(idleStrategy).idle(0);
    }

    @Test
    void runProcessesMultipleIterations() {
        IdleStrategy idleStrategy = mock(IdleStrategy.class);
        AeronTradeSubscriber sub = new AeronTradeSubscriber(subscription, engine, idleStrategy);
        when(subscription.streamId()).thenReturn(1);
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        when(subscription.poll(any(FragmentHandler.class), eq(256)))
            .thenAnswer(invocation -> {
                if (callCount.incrementAndGet() >= 3) {
                    sub.stop();
                }
                return 5;
            });

        sub.run();

        verify(subscription, times(3)).poll(any(FragmentHandler.class), eq(256));
        verify(idleStrategy, times(3)).idle(5);
    }
}
