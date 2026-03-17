package com.coindcx.makertaker.model;

public final class WindowState {
    private final TimeWindow window;
    private final TumblingBucket[] buckets;
    private long totalMakerCount;
    private long totalTakerCount;

    public WindowState(TimeWindow window) {
        this.window = window;
        this.buckets = new TumblingBucket[window.getBucketCount()];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new TumblingBucket();
        }
        this.totalMakerCount = 0;
        this.totalTakerCount = 0;
    }

    public void record(long eventTimestampMs, boolean isMaker) {
        long bucketDurationMs = window.getBucketDurationMs();
        int bucketIndex = (int) ((eventTimestampMs / bucketDurationMs) % buckets.length);

        TumblingBucket bucket = buckets[bucketIndex];
        long expectedStart = (eventTimestampMs / bucketDurationMs) * bucketDurationMs;

        if (bucket.getBucketStartEpochMs() != expectedStart) {
            totalMakerCount -= bucket.getMakerCount();
            totalTakerCount -= bucket.getTakerCount();
            bucket.reset(expectedStart);
        }

        if (isMaker) {
            bucket.incrementMaker();
            totalMakerCount++;
        } else {
            bucket.incrementTaker();
            totalTakerCount++;
        }
    }

    public void expireOldBuckets(long currentTimeMs) {
        long cutoffTime = currentTimeMs - window.getWindowDurationMs();
        for (TumblingBucket bucket : buckets) {
            if (bucket.isActive() && bucket.getBucketStartEpochMs() < cutoffTime) {
                totalMakerCount -= bucket.getMakerCount();
                totalTakerCount -= bucket.getTakerCount();
                bucket.reset(0);
            }
        }
    }

    public long getMakerCount() {
        return totalMakerCount;
    }

    public long getTakerCount() {
        return totalTakerCount;
    }

    public double getRatio() {
        long total = totalMakerCount + totalTakerCount;
        return total == 0 ? 0.0 : (double) totalMakerCount / total;
    }

    public TimeWindow getWindow() {
        return window;
    }

    TumblingBucket[] getBuckets() {
        return buckets;
    }
}
