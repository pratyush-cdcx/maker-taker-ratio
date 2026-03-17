package com.coindcx.makertaker.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TumblingBucketTest {

    @Test
    void defaultConstructorInitializesToZero() {
        TumblingBucket bucket = new TumblingBucket();
        assertEquals(0L, bucket.getBucketStartEpochMs());
        assertEquals(0L, bucket.getMakerCount());
        assertEquals(0L, bucket.getTakerCount());
    }

    @Test
    void newBucketIsNotActive() {
        TumblingBucket bucket = new TumblingBucket();
        assertFalse(bucket.isActive());
    }

    @Test
    void resetSetsStartTimeAndClearsCounts() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.incrementMaker();
        bucket.incrementTaker();

        bucket.reset(1000L);

        assertEquals(1000L, bucket.getBucketStartEpochMs());
        assertEquals(0L, bucket.getMakerCount());
        assertEquals(0L, bucket.getTakerCount());
    }

    @Test
    void resetWithPositiveTimestampMakesBucketActive() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.reset(5000L);
        assertTrue(bucket.isActive());
    }

    @Test
    void resetWithZeroTimestampMakesBucketInactive() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.reset(1000L);
        assertTrue(bucket.isActive());

        bucket.reset(0);
        assertFalse(bucket.isActive());
    }

    @Test
    void incrementMakerIncrementsCorrectCounter() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.incrementMaker();
        bucket.incrementMaker();
        bucket.incrementMaker();
        assertEquals(3L, bucket.getMakerCount());
        assertEquals(0L, bucket.getTakerCount());
    }

    @Test
    void incrementTakerIncrementsCorrectCounter() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.incrementTaker();
        bucket.incrementTaker();
        assertEquals(0L, bucket.getMakerCount());
        assertEquals(2L, bucket.getTakerCount());
    }

    @Test
    void mixedIncrementsTrackIndependently() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.incrementMaker();
        bucket.incrementTaker();
        bucket.incrementMaker();
        bucket.incrementTaker();
        bucket.incrementTaker();
        assertEquals(2L, bucket.getMakerCount());
        assertEquals(3L, bucket.getTakerCount());
    }

    @Test
    void resetClearsCountsAfterIncrements() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.incrementMaker();
        bucket.incrementMaker();
        bucket.incrementTaker();

        bucket.reset(2000L);

        assertEquals(2000L, bucket.getBucketStartEpochMs());
        assertEquals(0L, bucket.getMakerCount());
        assertEquals(0L, bucket.getTakerCount());
    }

    @Test
    void multipleResetsOverwritePreviousState() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.reset(1000L);
        bucket.incrementMaker();

        bucket.reset(2000L);
        assertEquals(2000L, bucket.getBucketStartEpochMs());
        assertEquals(0L, bucket.getMakerCount());

        bucket.incrementTaker();
        assertEquals(1L, bucket.getTakerCount());
    }

    @Test
    void isActiveReturnsFalseForZeroStart() {
        TumblingBucket bucket = new TumblingBucket();
        assertFalse(bucket.isActive());
    }

    @Test
    void isActiveReturnsTrueForPositiveStart() {
        TumblingBucket bucket = new TumblingBucket();
        bucket.reset(1L);
        assertTrue(bucket.isActive());
    }

    @Test
    void largeCountValues() {
        TumblingBucket bucket = new TumblingBucket();
        for (int i = 0; i < 100_000; i++) {
            bucket.incrementMaker();
        }
        assertEquals(100_000L, bucket.getMakerCount());
    }
}
