package com.coindcx.makertaker.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class WindowStateTest {

    @ParameterizedTest
    @EnumSource(TimeWindow.class)
    void constructorInitializesForAllWindows(TimeWindow tw) {
        WindowState state = new WindowState(tw);
        assertEquals(tw, state.getWindow());
        assertEquals(0L, state.getMakerCount());
        assertEquals(0L, state.getTakerCount());
        assertEquals(0.0, state.getRatio());
    }

    @ParameterizedTest
    @EnumSource(TimeWindow.class)
    void bucketArrayHasCorrectLength(TimeWindow tw) {
        WindowState state = new WindowState(tw);
        assertEquals(tw.getBucketCount(), state.getBuckets().length);
    }

    @ParameterizedTest
    @EnumSource(TimeWindow.class)
    void allBucketsAreInitializedAndInactive(TimeWindow tw) {
        WindowState state = new WindowState(tw);
        for (TumblingBucket bucket : state.getBuckets()) {
            assertNotNull(bucket);
            assertFalse(bucket.isActive());
        }
    }

    @Test
    void ratioIsZeroWithNoCounts() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        assertEquals(0.0, state.getRatio());
    }

    @Test
    void recordMakerIncrementsMakerCount() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long ts = 60_000L;
        state.record(ts, true);
        assertEquals(1L, state.getMakerCount());
        assertEquals(0L, state.getTakerCount());
    }

    @Test
    void recordTakerIncrementsTakerCount() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long ts = 60_000L;
        state.record(ts, false);
        assertEquals(0L, state.getMakerCount());
        assertEquals(1L, state.getTakerCount());
    }

    @Test
    void ratioIsOneForAllMaker() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long ts = 60_000L;
        for (int i = 0; i < 10; i++) {
            state.record(ts + i, true);
        }
        assertEquals(1.0, state.getRatio(), 1e-9);
    }

    @Test
    void ratioIsZeroForAllTaker() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long ts = 60_000L;
        for (int i = 0; i < 10; i++) {
            state.record(ts + i, false);
        }
        assertEquals(0.0, state.getRatio(), 1e-9);
    }

    @Test
    void ratioIsHalfForEqualCounts() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long ts = 60_000L;
        for (int i = 0; i < 50; i++) {
            state.record(ts + i, true);
            state.record(ts + i, false);
        }
        assertEquals(0.5, state.getRatio(), 1e-9);
    }

    @Test
    void ratioCalculation() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long ts = 60_000L;
        state.record(ts, true);
        state.record(ts + 1, true);
        state.record(ts + 2, false);
        assertEquals(2.0 / 3.0, state.getRatio(), 1e-9);
    }

    @Test
    void recordInDifferentBucketsAccumulatesTotals() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDurationMs = TimeWindow.HOUR_1.getBucketDurationMs();

        state.record(bucketDurationMs * 1, true);
        state.record(bucketDurationMs * 2, false);
        state.record(bucketDurationMs * 3, true);

        assertEquals(2L, state.getMakerCount());
        assertEquals(1L, state.getTakerCount());
    }

    @Test
    void bucketRotationSubtractsOldCounts() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDuration = TimeWindow.HOUR_1.getBucketDurationMs();
        int bucketCount = TimeWindow.HOUR_1.getBucketCount();

        long ts1 = bucketDuration;
        state.record(ts1, true);
        state.record(ts1 + 1, true);
        state.record(ts1 + 2, false);
        assertEquals(2L, state.getMakerCount());
        assertEquals(1L, state.getTakerCount());

        long ts2 = ts1 + (long) bucketCount * bucketDuration;
        state.record(ts2, false);

        assertEquals(0L, state.getMakerCount());
        assertEquals(1L, state.getTakerCount());
    }

    @Test
    void expireOldBucketsRemovesExpiredData() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDuration = TimeWindow.HOUR_1.getBucketDurationMs();
        long windowDuration = TimeWindow.HOUR_1.getWindowDurationMs();

        long ts = bucketDuration;
        state.record(ts, true);
        state.record(ts, false);

        assertEquals(1L, state.getMakerCount());
        assertEquals(1L, state.getTakerCount());

        long futureTime = ts + windowDuration + bucketDuration;
        state.expireOldBuckets(futureTime);

        assertEquals(0L, state.getMakerCount());
        assertEquals(0L, state.getTakerCount());
        assertEquals(0.0, state.getRatio());
    }

    @Test
    void expireOldBucketsKeepsRecentData() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDuration = TimeWindow.HOUR_1.getBucketDurationMs();

        long ts = bucketDuration * 5;
        state.record(ts, true);
        state.record(ts, true);

        state.expireOldBuckets(ts + bucketDuration);

        assertEquals(2L, state.getMakerCount());
        assertEquals(1.0, state.getRatio(), 1e-9);
    }

    @Test
    void expireOldBucketsOnEmptyStateIsNoOp() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        state.expireOldBuckets(System.currentTimeMillis());
        assertEquals(0L, state.getMakerCount());
        assertEquals(0L, state.getTakerCount());
    }

    @Test
    void multipleRecordsInSameBucket() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDuration = TimeWindow.HOUR_1.getBucketDurationMs();
        long bucketStart = bucketDuration * 10;

        for (int i = 0; i < 100; i++) {
            state.record(bucketStart + i, i % 3 == 0);
        }

        long expectedMaker = 0;
        for (int i = 0; i < 100; i++) {
            if (i % 3 == 0) expectedMaker++;
        }
        assertEquals(expectedMaker, state.getMakerCount());
        assertEquals(100 - expectedMaker, state.getTakerCount());
    }

    @Test
    void recordsSpanningMultipleBucketsInHour24() {
        WindowState state = new WindowState(TimeWindow.HOUR_24);
        long bucketDuration = TimeWindow.HOUR_24.getBucketDurationMs();

        for (int i = 0; i < 10; i++) {
            state.record(bucketDuration * (i + 1), true);
        }
        assertEquals(10L, state.getMakerCount());
        assertEquals(0L, state.getTakerCount());
        assertEquals(1.0, state.getRatio(), 1e-9);
    }

    @Test
    void getWindowReturnsCorrectWindow() {
        WindowState state = new WindowState(TimeWindow.DAY_7);
        assertSame(TimeWindow.DAY_7, state.getWindow());
    }

    @Test
    void expireDoesNotAffectInactiveBuckets() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        state.expireOldBuckets(Long.MAX_VALUE);
        assertEquals(0L, state.getMakerCount());
        assertEquals(0L, state.getTakerCount());
    }

    @Test
    void rotationHandlesWraparound() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDuration = TimeWindow.HOUR_1.getBucketDurationMs();
        int bucketCount = TimeWindow.HOUR_1.getBucketCount();

        for (int i = 0; i < bucketCount + 10; i++) {
            state.record(bucketDuration * (i + 1), true);
        }

        assertEquals(bucketCount, state.getMakerCount());
    }

    @Test
    void countsNeverGoNegativeAfterExpiry() {
        WindowState state = new WindowState(TimeWindow.HOUR_1);
        long bucketDuration = TimeWindow.HOUR_1.getBucketDurationMs();

        state.record(bucketDuration, true);
        state.record(bucketDuration, false);

        state.expireOldBuckets(Long.MAX_VALUE);

        assertTrue(state.getMakerCount() >= 0);
        assertTrue(state.getTakerCount() >= 0);
    }
}
