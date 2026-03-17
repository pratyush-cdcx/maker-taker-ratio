package com.coindcx.makertaker.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowTest {

    @Test
    void valuesContainsAllFourWindows() {
        TimeWindow[] values = TimeWindow.values();
        assertEquals(4, values.length);
        assertArrayEquals(
            new TimeWindow[]{TimeWindow.HOUR_1, TimeWindow.HOUR_24, TimeWindow.DAY_7, TimeWindow.DAY_30},
            values
        );
    }

    @Test
    void hour1HasCorrectDurations() {
        assertEquals(Duration.ofHours(1), TimeWindow.HOUR_1.getWindowDuration());
        assertEquals(Duration.ofMinutes(1), TimeWindow.HOUR_1.getBucketDuration());
        assertEquals(60_000L, TimeWindow.HOUR_1.getBucketDurationMs());
        assertEquals(3_600_000L, TimeWindow.HOUR_1.getWindowDurationMs());
        assertEquals(60, TimeWindow.HOUR_1.getBucketCount());
    }

    @Test
    void hour24HasCorrectDurations() {
        assertEquals(Duration.ofHours(24), TimeWindow.HOUR_24.getWindowDuration());
        assertEquals(Duration.ofMinutes(5), TimeWindow.HOUR_24.getBucketDuration());
        assertEquals(300_000L, TimeWindow.HOUR_24.getBucketDurationMs());
        assertEquals(86_400_000L, TimeWindow.HOUR_24.getWindowDurationMs());
        assertEquals(288, TimeWindow.HOUR_24.getBucketCount());
    }

    @Test
    void day7HasCorrectDurations() {
        assertEquals(Duration.ofDays(7), TimeWindow.DAY_7.getWindowDuration());
        assertEquals(Duration.ofHours(1), TimeWindow.DAY_7.getBucketDuration());
        assertEquals(3_600_000L, TimeWindow.DAY_7.getBucketDurationMs());
        assertEquals(604_800_000L, TimeWindow.DAY_7.getWindowDurationMs());
        assertEquals(168, TimeWindow.DAY_7.getBucketCount());
    }

    @Test
    void day30HasCorrectDurations() {
        assertEquals(Duration.ofDays(30), TimeWindow.DAY_30.getWindowDuration());
        assertEquals(Duration.ofHours(1), TimeWindow.DAY_30.getBucketDuration());
        assertEquals(3_600_000L, TimeWindow.DAY_30.getBucketDurationMs());
        assertEquals(2_592_000_000L, TimeWindow.DAY_30.getWindowDurationMs());
        assertEquals(720, TimeWindow.DAY_30.getBucketCount());
    }

    @Test
    void bucketCountMatchesWindowOverBucketDuration() {
        for (TimeWindow tw : TimeWindow.values()) {
            long expectedBuckets = tw.getWindowDuration().toMillis() / tw.getBucketDuration().toMillis();
            assertEquals(expectedBuckets, tw.getBucketCount(),
                "bucket count mismatch for " + tw);
        }
    }

    @Test
    void labelReturnsExpectedStrings() {
        assertEquals("1H", TimeWindow.HOUR_1.label());
        assertEquals("24H", TimeWindow.HOUR_24.label());
        assertEquals("7D", TimeWindow.DAY_7.label());
        assertEquals("30D", TimeWindow.DAY_30.label());
    }

    @ParameterizedTest
    @EnumSource(TimeWindow.class)
    void fromLabelRoundTripsForAllValues(TimeWindow tw) {
        assertEquals(tw, TimeWindow.fromLabel(tw.label()));
    }

    @Test
    void fromLabelIsCaseInsensitive() {
        assertEquals(TimeWindow.HOUR_1, TimeWindow.fromLabel("1h"));
        assertEquals(TimeWindow.HOUR_24, TimeWindow.fromLabel("24h"));
        assertEquals(TimeWindow.DAY_7, TimeWindow.fromLabel("7d"));
        assertEquals(TimeWindow.DAY_30, TimeWindow.fromLabel("30d"));
    }

    @Test
    void fromLabelMixedCase() {
        assertEquals(TimeWindow.HOUR_1, TimeWindow.fromLabel("1H"));
        assertEquals(TimeWindow.DAY_7, TimeWindow.fromLabel("7D"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2H", "48H", "14D", "INVALID", "1M"})
    void fromLabelThrowsForUnknownLabel(String label) {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> TimeWindow.fromLabel(label)
        );
        assertTrue(ex.getMessage().contains("Unknown time window"));
    }

    @Test
    void valueOfReturnsCorrectEnum() {
        assertEquals(TimeWindow.HOUR_1, TimeWindow.valueOf("HOUR_1"));
        assertEquals(TimeWindow.HOUR_24, TimeWindow.valueOf("HOUR_24"));
        assertEquals(TimeWindow.DAY_7, TimeWindow.valueOf("DAY_7"));
        assertEquals(TimeWindow.DAY_30, TimeWindow.valueOf("DAY_30"));
    }

    @Test
    void valueOfThrowsForInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.valueOf("HOUR_2"));
    }
}
