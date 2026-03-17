package com.coindcx.makertaker.model;

import com.coindcx.makertaker.model.UserSymbolState.WindowSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UserSymbolStateTest {

    @Test
    void constructorInitializesFields() {
        UserSymbolState state = new UserSymbolState(42L, "BTCUSDT");
        assertEquals(42L, state.getUserId());
        assertEquals("BTCUSDT", state.getSymbol());
        assertEquals(0L, state.getLastUpdatedTimestamp());
    }

    @Test
    void constructorWithNullSymbol() {
        UserSymbolState state = new UserSymbolState(1L, null);
        assertNull(state.getSymbol());
    }

    @Test
    void recordUpdatesTimestampAndCounts() {
        UserSymbolState state = new UserSymbolState(1L, "SYM");
        state.record(60_000L, true);
        assertEquals(60_000L, state.getLastUpdatedTimestamp());
        assertEquals(1L, state.getMakerCount(TimeWindow.HOUR_1));
        assertEquals(0L, state.getTakerCount(TimeWindow.HOUR_1));
    }

    @Test
    void recordTakerUpdatesCorrectly() {
        UserSymbolState state = new UserSymbolState(1L, "SYM");
        state.record(60_000L, false);
        assertEquals(0L, state.getMakerCount(TimeWindow.HOUR_1));
        assertEquals(1L, state.getTakerCount(TimeWindow.HOUR_1));
    }

    @Test
    void recordMultipleUpdatesAllWindows() {
        UserSymbolState state = new UserSymbolState(1L, "SYM");
        state.record(60_000L, true);
        state.record(60_001L, false);

        for (TimeWindow tw : TimeWindow.values()) {
            assertEquals(1L, state.getMakerCount(tw));
            assertEquals(1L, state.getTakerCount(tw));
            assertEquals(0.5, state.getRatio(tw), 1e-9);
        }
    }

    @Test
    void snapshotReturnsCorrectData() {
        UserSymbolState state = new UserSymbolState(42L, "ETH");
        state.record(60_000L, true);
        state.record(60_001L, true);
        state.record(60_002L, false);

        WindowSnapshot snap = state.snapshot();
        assertEquals(42L, snap.userId());
        assertEquals("ETH", snap.symbol());
        assertEquals(60_002L, snap.lastUpdatedTimestamp());
        assertEquals(4, snap.makerCounts().length);
        assertEquals(4, snap.takerCounts().length);
        assertEquals(4, snap.ratios().length);

        for (int i = 0; i < 4; i++) {
            assertEquals(2L, snap.makerCounts()[i]);
            assertEquals(1L, snap.takerCounts()[i]);
            assertEquals(2.0 / 3.0, snap.ratios()[i], 1e-9);
        }
    }

    @Test
    void snapshotWithNullSymbol() {
        UserSymbolState state = new UserSymbolState(1L, null);
        state.record(60_000L, true);
        WindowSnapshot snap = state.snapshot();
        assertNull(snap.symbol());
    }

    @Test
    void expireOldBucketsRemovesExpiredData() {
        UserSymbolState state = new UserSymbolState(1L, "SYM");
        long ts = 60_000L;
        state.record(ts, true);
        state.record(ts, false);

        long futureTime = ts + TimeWindow.HOUR_1.getWindowDurationMs()
            + TimeWindow.HOUR_1.getBucketDurationMs();
        state.expireOldBuckets(futureTime);

        assertEquals(0L, state.getMakerCount(TimeWindow.HOUR_1));
        assertEquals(0L, state.getTakerCount(TimeWindow.HOUR_1));
    }

    @Test
    void ratioIsZeroWhenEmpty() {
        UserSymbolState state = new UserSymbolState(1L, "SYM");
        for (TimeWindow tw : TimeWindow.values()) {
            assertEquals(0.0, state.getRatio(tw));
        }
    }

    @Test
    void visitPassesCorrectValues() {
        UserSymbolState state = new UserSymbolState(42L, "BTCUSDT");
        state.record(60_000L, true);
        state.record(60_001L, true);
        state.record(60_002L, false);

        AtomicLong visitedUserId = new AtomicLong();
        AtomicReference<String> visitedSymbol = new AtomicReference<>();
        AtomicLong visitedTimestamp = new AtomicLong();
        long[] visitedMaker = new long[4];
        long[] visitedTaker = new long[4];
        double[] visitedRatio = new double[4];

        state.visit((userId, symbol, timestamp,
                     m1H, t1H, r1H,
                     m24H, t24H, r24H,
                     m7D, t7D, r7D,
                     m30D, t30D, r30D) -> {
            visitedUserId.set(userId);
            visitedSymbol.set(symbol);
            visitedTimestamp.set(timestamp);
            visitedMaker[0] = m1H;
            visitedMaker[1] = m24H;
            visitedMaker[2] = m7D;
            visitedMaker[3] = m30D;
            visitedTaker[0] = t1H;
            visitedTaker[1] = t24H;
            visitedTaker[2] = t7D;
            visitedTaker[3] = t30D;
            visitedRatio[0] = r1H;
            visitedRatio[1] = r24H;
            visitedRatio[2] = r7D;
            visitedRatio[3] = r30D;
        });

        assertEquals(42L, visitedUserId.get());
        assertEquals("BTCUSDT", visitedSymbol.get());
        assertEquals(60_002L, visitedTimestamp.get());

        for (int i = 0; i < 4; i++) {
            assertEquals(2L, visitedMaker[i]);
            assertEquals(1L, visitedTaker[i]);
            assertEquals(2.0 / 3.0, visitedRatio[i], 1e-9);
        }
    }

    @Test
    void visitWithNullSymbol() {
        UserSymbolState state = new UserSymbolState(1L, null);
        state.record(60_000L, true);

        AtomicReference<String> visitedSymbol = new AtomicReference<>("not-null");

        state.visit((userId, symbol, timestamp,
                     m1H, t1H, r1H, m24H, t24H, r24H,
                     m7D, t7D, r7D, m30D, t30D, r30D) ->
            visitedSymbol.set(symbol));

        assertNull(visitedSymbol.get());
    }

    @Test
    void visitWithEmptyState() {
        UserSymbolState state = new UserSymbolState(99L, "ETH");

        AtomicLong visitedUserId = new AtomicLong();
        long[] maker = new long[4];

        state.visit((userId, symbol, timestamp,
                     m1H, t1H, r1H, m24H, t24H, r24H,
                     m7D, t7D, r7D, m30D, t30D, r30D) -> {
            visitedUserId.set(userId);
            maker[0] = m1H;
            maker[1] = m24H;
            maker[2] = m7D;
            maker[3] = m30D;
        });

        assertEquals(99L, visitedUserId.get());
        for (long m : maker) {
            assertEquals(0L, m);
        }
    }

    @Test
    void visitMatchesSnapshot() {
        UserSymbolState state = new UserSymbolState(42L, "BTC");
        state.record(60_000L, true);
        state.record(60_001L, false);
        state.record(60_002L, true);
        state.record(60_003L, true);

        WindowSnapshot snap = state.snapshot();
        long[] visitMaker = new long[4];
        long[] visitTaker = new long[4];
        double[] visitRatio = new double[4];

        state.visit((userId, symbol, timestamp,
                     m1H, t1H, r1H, m24H, t24H, r24H,
                     m7D, t7D, r7D, m30D, t30D, r30D) -> {
            visitMaker[0] = m1H; visitMaker[1] = m24H; visitMaker[2] = m7D; visitMaker[3] = m30D;
            visitTaker[0] = t1H; visitTaker[1] = t24H; visitTaker[2] = t7D; visitTaker[3] = t30D;
            visitRatio[0] = r1H; visitRatio[1] = r24H; visitRatio[2] = r7D; visitRatio[3] = r30D;
        });

        assertArrayEquals(snap.makerCounts(), visitMaker);
        assertArrayEquals(snap.takerCounts(), visitTaker);
        assertArrayEquals(snap.ratios(), visitRatio, 1e-15);
    }
}
