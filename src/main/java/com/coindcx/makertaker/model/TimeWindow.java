package com.coindcx.makertaker.model;

import java.time.Duration;

public enum TimeWindow {
    HOUR_1(Duration.ofHours(1), Duration.ofMinutes(1), 60),
    HOUR_24(Duration.ofHours(24), Duration.ofMinutes(5), 288),
    DAY_7(Duration.ofDays(7), Duration.ofHours(1), 168),
    DAY_30(Duration.ofDays(30), Duration.ofHours(1), 720);

    private final Duration windowDuration;
    private final Duration bucketDuration;
    private final int bucketCount;

    TimeWindow(Duration windowDuration, Duration bucketDuration, int bucketCount) {
        this.windowDuration = windowDuration;
        this.bucketDuration = bucketDuration;
        this.bucketCount = bucketCount;
    }

    public Duration getWindowDuration() {
        return windowDuration;
    }

    public Duration getBucketDuration() {
        return bucketDuration;
    }

    public long getBucketDurationMs() {
        return bucketDuration.toMillis();
    }

    public long getWindowDurationMs() {
        return windowDuration.toMillis();
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public String label() {
        return switch (this) {
            case HOUR_1 -> "1H";
            case HOUR_24 -> "24H";
            case DAY_7 -> "7D";
            case DAY_30 -> "30D";
        };
    }

    public static TimeWindow fromLabel(String label) {
        return switch (label.toUpperCase()) {
            case "1H" -> HOUR_1;
            case "24H" -> HOUR_24;
            case "7D" -> DAY_7;
            case "30D" -> DAY_30;
            default -> throw new IllegalArgumentException("Unknown time window: " + label);
        };
    }
}
