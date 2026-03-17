package com.coindcx.makertaker.state;

import com.coindcx.makertaker.model.UserSymbolState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRatioStoreTest {

    private InMemoryRatioStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRatioStore();
    }

    @Test
    void getOrCreateOverallCreatesNewState() {
        UserSymbolState state = store.getOrCreateOverall(1L);

        assertNotNull(state);
        assertEquals(1L, state.getUserId());
        assertNull(state.getSymbol());
    }

    @Test
    void getOrCreateOverallReturnsSameInstanceForSameUser() {
        UserSymbolState first = store.getOrCreateOverall(1L);
        UserSymbolState second = store.getOrCreateOverall(1L);

        assertSame(first, second);
    }

    @Test
    void getOrCreateOverallReturnsDistinctInstancesForDifferentUsers() {
        UserSymbolState state1 = store.getOrCreateOverall(1L);
        UserSymbolState state2 = store.getOrCreateOverall(2L);

        assertNotSame(state1, state2);
        assertEquals(1L, state1.getUserId());
        assertEquals(2L, state2.getUserId());
    }

    @Test
    void getOrCreatePerSymbolCreatesNewState() {
        UserSymbolState state = store.getOrCreatePerSymbol(1L, "BTCINR");

        assertNotNull(state);
        assertEquals(1L, state.getUserId());
        assertEquals("BTCINR", state.getSymbol());
    }

    @Test
    void getOrCreatePerSymbolReturnsSameInstanceForSameKey() {
        UserSymbolState first = store.getOrCreatePerSymbol(1L, "BTCINR");
        UserSymbolState second = store.getOrCreatePerSymbol(1L, "BTCINR");

        assertSame(first, second);
    }

    @Test
    void getOrCreatePerSymbolReturnsDistinctForDifferentSymbols() {
        UserSymbolState btc = store.getOrCreatePerSymbol(1L, "BTCINR");
        UserSymbolState eth = store.getOrCreatePerSymbol(1L, "ETHINR");

        assertNotSame(btc, eth);
        assertEquals("BTCINR", btc.getSymbol());
        assertEquals("ETHINR", eth.getSymbol());
    }

    @Test
    void getOrCreatePerSymbolReturnsDistinctForDifferentUsers() {
        UserSymbolState state1 = store.getOrCreatePerSymbol(1L, "BTCINR");
        UserSymbolState state2 = store.getOrCreatePerSymbol(2L, "BTCINR");

        assertNotSame(state1, state2);
        assertEquals(1L, state1.getUserId());
        assertEquals(2L, state2.getUserId());
    }

    @Test
    void getOrCreatePerSymbolWithNullSymbolThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
            store.getOrCreatePerSymbol(1L, null)
        );
    }

    @Test
    void getOverallReturnsNullForNonExistentUser() {
        assertNull(store.getOverall(999L));
    }

    @Test
    void getOverallReturnsStateAfterCreation() {
        store.getOrCreateOverall(1L);

        UserSymbolState state = store.getOverall(1L);
        assertNotNull(state);
        assertEquals(1L, state.getUserId());
    }

    @Test
    void getPerSymbolReturnsNullForNonExistentKey() {
        assertNull(store.getPerSymbol(999L, "BTCINR"));
    }

    @Test
    void getPerSymbolReturnsNullForNullSymbol() {
        store.getOrCreatePerSymbol(1L, "BTCINR");
        assertThrows(NullPointerException.class, () ->
            store.getPerSymbol(1L, null)
        );
    }

    @Test
    void getPerSymbolReturnsStateAfterCreation() {
        store.getOrCreatePerSymbol(1L, "BTCINR");

        UserSymbolState state = store.getPerSymbol(1L, "BTCINR");
        assertNotNull(state);
        assertEquals("BTCINR", state.getSymbol());
    }

    @Test
    void overallSizeStartsAtZero() {
        assertEquals(0, store.overallSize());
    }

    @Test
    void overallSizeIncrementsOnNewUsers() {
        store.getOrCreateOverall(1L);
        assertEquals(1, store.overallSize());

        store.getOrCreateOverall(2L);
        assertEquals(2, store.overallSize());

        store.getOrCreateOverall(3L);
        assertEquals(3, store.overallSize());
    }

    @Test
    void overallSizeDoesNotIncrementForExistingUser() {
        store.getOrCreateOverall(1L);
        store.getOrCreateOverall(1L);

        assertEquals(1, store.overallSize());
    }

    @Test
    void perSymbolSizeStartsAtZero() {
        assertEquals(0, store.perSymbolSize());
    }

    @Test
    void perSymbolSizeIncrementsOnNewEntries() {
        store.getOrCreatePerSymbol(1L, "BTCINR");
        assertEquals(1, store.perSymbolSize());

        store.getOrCreatePerSymbol(1L, "ETHINR");
        assertEquals(2, store.perSymbolSize());

        store.getOrCreatePerSymbol(2L, "BTCINR");
        assertEquals(3, store.perSymbolSize());
    }

    @Test
    void perSymbolSizeDoesNotIncrementForExistingKey() {
        store.getOrCreatePerSymbol(1L, "BTCINR");
        store.getOrCreatePerSymbol(1L, "BTCINR");

        assertEquals(1, store.perSymbolSize());
    }

    @Test
    void overallAndPerSymbolSizesAreIndependent() {
        store.getOrCreateOverall(1L);
        store.getOrCreatePerSymbol(1L, "BTCINR");

        assertEquals(1, store.overallSize());
        assertEquals(1, store.perSymbolSize());
    }

    @Test
    void concurrentGetOrCreateOverallReturnsSameInstance() throws Exception {
        int threadCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<UserSymbolState>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return store.getOrCreateOverall(42L);
            }));
        }

        UserSymbolState first = futures.getFirst().get();
        for (Future<UserSymbolState> f : futures) {
            assertSame(first, f.get());
        }
        assertEquals(1, store.overallSize());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentGetOrCreatePerSymbolReturnsSameInstance() throws Exception {
        int threadCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<UserSymbolState>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return store.getOrCreatePerSymbol(42L, "BTCINR");
            }));
        }

        UserSymbolState first = futures.getFirst().get();
        for (Future<UserSymbolState> f : futures) {
            assertSame(first, f.get());
        }
        assertEquals(1, store.perSymbolSize());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentMixedOperationsDoNotCorruptStore() throws Exception {
        int threadCount = 32;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        long userId = threadId % 8;
                        String symbol = "SYM" + (i % 4);
                        store.getOrCreateOverall(userId);
                        store.getOrCreatePerSymbol(userId, symbol);
                        store.getOverall(userId);
                        store.getPerSymbol(userId, symbol);
                    }
                } catch (Exception e) {
                    fail("Concurrent operation threw: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertTrue(store.overallSize() <= 8);
        assertTrue(store.perSymbolSize() <= 32);
        assertTrue(store.overallSize() > 0);
        assertTrue(store.perSymbolSize() > 0);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void getOrCreateOverallWithZeroUserId() {
        UserSymbolState state = store.getOrCreateOverall(0L);
        assertNotNull(state);
        assertEquals(0L, state.getUserId());
    }

    @Test
    void getOrCreateOverallWithNegativeUserId() {
        UserSymbolState state = store.getOrCreateOverall(-1L);
        assertNotNull(state);
        assertEquals(-1L, state.getUserId());
    }

    @Test
    void getOrCreatePerSymbolWithEmptySymbol() {
        UserSymbolState state = store.getOrCreatePerSymbol(1L, "");
        assertNotNull(state);
        assertEquals("", state.getSymbol());
    }

    @Test
    void recordingThroughStoreUpdatesState() {
        UserSymbolState state = store.getOrCreateOverall(1L);
        state.record(1000L, true);
        state.record(2000L, false);

        UserSymbolState retrieved = store.getOverall(1L);
        UserSymbolState.WindowSnapshot snap = retrieved.snapshot();
        for (long mc : snap.makerCounts()) assertEquals(1, mc);
        for (long tc : snap.takerCounts()) assertEquals(1, tc);
    }
}
