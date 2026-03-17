package com.coindcx.makertaker.aeron;

import com.coindcx.makertaker.model.UserSymbolState;
import com.coindcx.makertaker.model.UserSymbolState.WindowSnapshot;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AeronRatioPublisherTest {

    @Mock
    private ExclusivePublication publication;

    private AeronRatioPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AeronRatioPublisher(publication);
    }

    private WindowSnapshot createSnapshot() {
        return new WindowSnapshot(
            42L,
            "BTCUSDT",
            new long[]{10, 20, 30, 40},
            new long[]{5, 15, 25, 35},
            new double[]{0.5, 0.6, 0.7, 0.8},
            System.currentTimeMillis()
        );
    }

    private UserSymbolState createStateWithTrades() {
        UserSymbolState state = new UserSymbolState(42L, "BTCUSDT");
        state.record(60_000L, true);
        state.record(60_001L, false);
        state.record(60_002L, true);
        return state;
    }

    @Test
    void publishSuccessful() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(100L);

        boolean result = publisher.publish(createSnapshot());

        assertTrue(result);
        verify(publication).offer(any(UnsafeBuffer.class), eq(0), anyInt());
    }

    @Test
    void publishBackPressuredReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.BACK_PRESSURED);

        boolean result = publisher.publish(createSnapshot());

        assertFalse(result);
        verify(publication, times(1)).offer(any(UnsafeBuffer.class), eq(0), anyInt());
    }

    @Test
    void publishNotConnectedReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.NOT_CONNECTED);

        boolean result = publisher.publish(createSnapshot());

        assertFalse(result);
    }

    @Test
    void publishAdminActionRetriesOnceAndReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.ADMIN_ACTION)
            .thenReturn(Publication.BACK_PRESSURED);

        boolean result = publisher.publish(createSnapshot());

        assertFalse(result);
        verify(publication, times(2)).offer(any(UnsafeBuffer.class), eq(0), anyInt());
    }

    @Test
    void publishAdminActionRetrySucceedsButStillReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.ADMIN_ACTION)
            .thenReturn(100L);

        boolean result = publisher.publish(createSnapshot());

        assertFalse(result);
        verify(publication, times(2)).offer(any(UnsafeBuffer.class), eq(0), anyInt());
    }

    @Test
    void publishWithNullSymbolUsesEmptyString() {
        WindowSnapshot snapshot = new WindowSnapshot(
            1L, null,
            new long[]{0, 0, 0, 0},
            new long[]{0, 0, 0, 0},
            new double[]{0.0, 0.0, 0.0, 0.0},
            1000L
        );
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(100L);

        assertTrue(publisher.publish(snapshot));
    }

    @Test
    void publishDirectSuccessful() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(100L);

        boolean result = publisher.publishDirect(createStateWithTrades());

        assertTrue(result);
        verify(publication).offer(any(UnsafeBuffer.class), eq(0), anyInt());
    }

    @Test
    void publishDirectBackPressuredReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.BACK_PRESSURED);

        boolean result = publisher.publishDirect(createStateWithTrades());

        assertFalse(result);
    }

    @Test
    void publishDirectNotConnectedReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.NOT_CONNECTED);

        boolean result = publisher.publishDirect(createStateWithTrades());

        assertFalse(result);
    }

    @Test
    void publishDirectAdminActionRetries() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.ADMIN_ACTION)
            .thenReturn(100L);

        boolean result = publisher.publishDirect(createStateWithTrades());

        assertFalse(result);
        verify(publication, times(2)).offer(any(UnsafeBuffer.class), eq(0), anyInt());
    }

    @Test
    void publishDirectWithNullSymbolState() {
        UserSymbolState state = new UserSymbolState(1L, null);
        state.record(60_000L, true);

        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(100L);

        assertTrue(publisher.publishDirect(state));
    }

    @Test
    void publishDirectWithEmptyState() {
        UserSymbolState state = new UserSymbolState(99L, "ETHINR");

        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(100L);

        assertTrue(publisher.publishDirect(state));
    }

    @Test
    void closeClosesPublication() {
        when(publication.isClosed()).thenReturn(false);

        publisher.close();

        verify(publication).close();
    }

    @Test
    void closeSkipsIfAlreadyClosed() {
        when(publication.isClosed()).thenReturn(true);

        publisher.close();

        verify(publication, never()).close();
    }

    @Test
    void publishUnknownNegativeResultReturnsFalse() {
        when(publication.offer(any(UnsafeBuffer.class), eq(0), anyInt()))
            .thenReturn(Publication.CLOSED);

        boolean result = publisher.publish(createSnapshot());

        assertFalse(result);
    }
}
