package com.coindcx.makertaker.model;

public final class TumblingBucket {
    private long bucketStartEpochMs;
    private long makerCount;
    private long takerCount;

    public TumblingBucket() {
        this.bucketStartEpochMs = 0;
        this.makerCount = 0;
        this.takerCount = 0;
    }

    public void reset(long newStartEpochMs) {
        this.bucketStartEpochMs = newStartEpochMs;
        this.makerCount = 0;
        this.takerCount = 0;
    }

    public void incrementMaker() {
        makerCount++;
    }

    public void incrementTaker() {
        takerCount++;
    }

    public long getBucketStartEpochMs() {
        return bucketStartEpochMs;
    }

    public long getMakerCount() {
        return makerCount;
    }

    public long getTakerCount() {
        return takerCount;
    }

    public boolean isActive() {
        return bucketStartEpochMs > 0;
    }
}
