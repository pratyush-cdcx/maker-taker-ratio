package com.coindcx.makertaker.engine;

import com.coindcx.makertaker.aeron.AeronRatioPublisher;
import com.coindcx.makertaker.kafka.KafkaRatioProducer;
import com.coindcx.makertaker.model.UserSymbolState;
import com.coindcx.makertaker.model.UserSymbolState.WindowSnapshot;
import com.coindcx.makertaker.state.InMemoryRatioStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RatioEngineTest {

    private InMemoryRatioStore store;

    @Mock
    private AeronRatioPublisher aeronPublisher;

    @Mock
    private KafkaRatioProducer kafkaProducer;

    private RatioEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryRatioStore();
    }

    private RatioEngine buildEngine(boolean withAeron, boolean withKafka) {
        return new RatioEngine(
            store,
            withAeron ? aeronPublisher : null,
            withKafka ? kafkaProducer : null
        );
    }

    @Test
    void processSingleTradePublishesToBothChannels() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);

        verify(aeronPublisher, times(2)).publishDirect(any(UserSymbolState.class));
        verify(kafkaProducer, times(2)).publish(any(WindowSnapshot.class));
    }

    @Test
    void processUpdatesOverallAndPerSymbolState() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);

        UserSymbolState overall = store.getOverall(42L);
        assertNotNull(overall);
        assertEquals(42L, overall.getUserId());
        assertNull(overall.getSymbol());

        UserSymbolState perSymbol = store.getPerSymbol(42L, "BTCINR");
        assertNotNull(perSymbol);
        assertEquals(42L, perSymbol.getUserId());
        assertEquals("BTCINR", perSymbol.getSymbol());
    }

    @Test
    void processRecordsMakerTradeCorrectly() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);

        UserSymbolState overall = store.getOverall(42L);
        WindowSnapshot snap = overall.snapshot();
        for (long mc : snap.makerCounts()) assertEquals(1, mc);
        for (long tc : snap.takerCounts()) assertEquals(0, tc);
    }

    @Test
    void processRecordsTakerTradeCorrectly() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 1, 50000L, 100L, 1000L, false);

        UserSymbolState overall = store.getOverall(42L);
        WindowSnapshot snap = overall.snapshot();
        for (long mc : snap.makerCounts()) assertEquals(0, mc);
        for (long tc : snap.takerCounts()) assertEquals(1, tc);
    }

    @Test
    void processMultipleTradesAccumulatesState() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);
        engine.process(2L, 101L, 42L, "BTCINR", 1, 50001L, 200L, 2000L, false);
        engine.process(3L, 102L, 42L, "BTCINR", 0, 50002L, 150L, 3000L, true);

        UserSymbolState overall = store.getOverall(42L);
        WindowSnapshot snap = overall.snapshot();
        for (long mc : snap.makerCounts()) assertEquals(2, mc);
        for (long tc : snap.takerCounts()) assertEquals(1, tc);
    }

    @Test
    void processMultipleSymbolsCreatesDistinctPerSymbolStates() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);
        engine.process(2L, 101L, 42L, "ETHINR", 1, 3000L, 200L, 2000L, false);

        assertNotNull(store.getPerSymbol(42L, "BTCINR"));
        assertNotNull(store.getPerSymbol(42L, "ETHINR"));
        assertNull(store.getPerSymbol(42L, "XRPINR"));

        assertEquals(1, store.overallSize());
        assertEquals(2, store.perSymbolSize());
    }

    @Test
    void processWithNullAeronPublisherDoesNotThrow() {
        engine = buildEngine(false, true);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true)
        );

        verifyNoInteractions(aeronPublisher);
        verify(kafkaProducer, times(2)).publish(any(WindowSnapshot.class));
    }

    @Test
    void processWithNullKafkaProducerDoesNotThrow() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, false);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true)
        );

        verify(aeronPublisher, times(2)).publishDirect(any(UserSymbolState.class));
        verifyNoInteractions(kafkaProducer);
    }

    @Test
    void processWithBothPublishersNullDoesNotThrow() {
        engine = buildEngine(false, false);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true)
        );

        assertNotNull(store.getOverall(42L));
        assertNotNull(store.getPerSymbol(42L, "BTCINR"));
    }

    @Test
    void processAeronPublisherExceptionIsCaughtAndKafkaStillCalled() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class)))
            .thenThrow(new RuntimeException("Aeron connection lost"));
        engine = buildEngine(true, true);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true)
        );

        verify(aeronPublisher, times(2)).publishDirect(any(UserSymbolState.class));
        verify(kafkaProducer, times(2)).publish(any(WindowSnapshot.class));
    }

    @Test
    void processKafkaProducerExceptionIsCaught() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        doThrow(new RuntimeException("Kafka unavailable")).when(kafkaProducer).publish(any());
        engine = buildEngine(true, true);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true)
        );

        verify(aeronPublisher, times(2)).publishDirect(any(UserSymbolState.class));
        verify(kafkaProducer, times(2)).publish(any(WindowSnapshot.class));
    }

    @Test
    void processBothPublishersThrowExceptionsDoNotPropagate() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class)))
            .thenThrow(new RuntimeException("Aeron error"));
        doThrow(new RuntimeException("Kafka error")).when(kafkaProducer).publish(any());
        engine = buildEngine(true, true);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true)
        );
    }

    @Test
    void processWithZeroTimestamp() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        assertDoesNotThrow(() ->
            engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 0L, true)
        );

        assertEquals(0L, store.getOverall(42L).getLastUpdatedTimestamp());
    }

    @Test
    void processVerifiesAeronReceivesCorrectState() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);

        ArgumentCaptor<UserSymbolState> captor = ArgumentCaptor.forClass(UserSymbolState.class);
        verify(aeronPublisher, times(2)).publishDirect(captor.capture());
        for (UserSymbolState state : captor.getAllValues()) {
            assertEquals(42L, state.getUserId());
        }
    }

    @Test
    void processPerSymbolSnapshotHasSymbolSet() {
        engine = buildEngine(false, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);

        ArgumentCaptor<WindowSnapshot> captor = ArgumentCaptor.forClass(WindowSnapshot.class);
        verify(kafkaProducer, times(2)).publish(captor.capture());
        boolean foundPerSymbol = captor.getAllValues().stream()
            .anyMatch(s -> "BTCINR".equals(s.symbol()));
        assertTrue(foundPerSymbol);
    }

    @Test
    void processOverallSnapshotHasNullSymbol() {
        engine = buildEngine(false, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 1000L, true);

        ArgumentCaptor<WindowSnapshot> captor = ArgumentCaptor.forClass(WindowSnapshot.class);
        verify(kafkaProducer, times(2)).publish(captor.capture());
        boolean foundOverall = captor.getAllValues().stream()
            .anyMatch(s -> s.symbol() == null);
        assertTrue(foundOverall);
    }

    @Test
    void getStoreReturnsSameStoreInstance() {
        engine = buildEngine(true, true);
        assertSame(store, engine.getStore());
    }

    @Test
    void processMultipleUsersCreatesDistinctOverallStates() {
        when(aeronPublisher.publishDirect(any(UserSymbolState.class))).thenReturn(true);
        engine = buildEngine(true, true);

        engine.process(1L, 100L, 10L, "BTCINR", 0, 50000L, 100L, 1000L, true);
        engine.process(2L, 101L, 20L, "BTCINR", 1, 50000L, 100L, 2000L, false);

        assertEquals(2, store.overallSize());
        assertEquals(2, store.perSymbolSize());

        WindowSnapshot snap10 = store.getOverall(10L).snapshot();
        for (long mc : snap10.makerCounts()) assertEquals(1, mc);
        for (long tc : snap10.takerCounts()) assertEquals(0, tc);

        WindowSnapshot snap20 = store.getOverall(20L).snapshot();
        for (long mc : snap20.makerCounts()) assertEquals(0, mc);
        for (long tc : snap20.takerCounts()) assertEquals(1, tc);
    }

    @Test
    void processTimestampPropagatedToKafkaSnapshot() {
        engine = buildEngine(false, true);

        engine.process(1L, 100L, 42L, "BTCINR", 0, 50000L, 100L, 5000L, true);

        ArgumentCaptor<WindowSnapshot> captor = ArgumentCaptor.forClass(WindowSnapshot.class);
        verify(kafkaProducer, times(2)).publish(captor.capture());
        for (WindowSnapshot snap : captor.getAllValues()) {
            assertEquals(5000L, snap.lastUpdatedTimestamp());
        }
    }
}
