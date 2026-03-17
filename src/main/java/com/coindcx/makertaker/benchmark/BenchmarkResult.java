package com.coindcx.makertaker.benchmark;

import org.HdrHistogram.Histogram;

public final class BenchmarkResult {
    private final String name;
    private final long messageCount;
    private final long elapsedNanos;
    private final Histogram latencyHistogram;

    public BenchmarkResult(String name, long messageCount, long elapsedNanos, Histogram latencyHistogram) {
        this.name = name;
        this.messageCount = messageCount;
        this.elapsedNanos = elapsedNanos;
        this.latencyHistogram = latencyHistogram;
    }

    public String getName() { return name; }
    public long getMessageCount() { return messageCount; }

    public double getThroughput() {
        return (double) messageCount / (elapsedNanos / 1_000_000_000.0);
    }

    public long getP50Nanos() { return latencyHistogram.getValueAtPercentile(50); }
    public long getP90Nanos() { return latencyHistogram.getValueAtPercentile(90); }
    public long getP99Nanos() { return latencyHistogram.getValueAtPercentile(99); }
    public long getP999Nanos() { return latencyHistogram.getValueAtPercentile(99.9); }
    public long getMaxNanos() { return latencyHistogram.getMaxValue(); }

    public String formatLatency(long nanos) {
        if (nanos < 1_000) return nanos + " ns";
        if (nanos < 1_000_000) return String.format("%.1f us", nanos / 1_000.0);
        return String.format("%.2f ms", nanos / 1_000_000.0);
    }

    @Override
    public String toString() {
        return String.format(
            "%-15s | Messages: %,d | Throughput: %,.0f msg/s | p50: %s | p90: %s | p99: %s | p999: %s | max: %s",
            name, messageCount, getThroughput(),
            formatLatency(getP50Nanos()), formatLatency(getP90Nanos()),
            formatLatency(getP99Nanos()), formatLatency(getP999Nanos()),
            formatLatency(getMaxNanos())
        );
    }

    public static void printComparison(BenchmarkResult aeron, BenchmarkResult kafka) {
        String line = "=".repeat(80);
        System.out.println();
        System.out.println(line);
        System.out.println("       Maker/Taker Ratio Service - Benchmark Results");
        System.out.println(line);
        System.out.printf("%-20s | %-25s | %-25s%n", "Metric", "Aeron IPC", "Kafka");
        System.out.println("-".repeat(80));
        System.out.printf("%-20s | %-25s | %-25s%n", "Messages",
            String.format("%,d", aeron.messageCount), String.format("%,d", kafka.messageCount));
        System.out.printf("%-20s | %-25s | %-25s%n", "Throughput (msg/s)",
            String.format("%,.0f", aeron.getThroughput()), String.format("%,.0f", kafka.getThroughput()));
        System.out.printf("%-20s | %-25s | %-25s%n", "Latency p50",
            aeron.formatLatency(aeron.getP50Nanos()), kafka.formatLatency(kafka.getP50Nanos()));
        System.out.printf("%-20s | %-25s | %-25s%n", "Latency p90",
            aeron.formatLatency(aeron.getP90Nanos()), kafka.formatLatency(kafka.getP90Nanos()));
        System.out.printf("%-20s | %-25s | %-25s%n", "Latency p99",
            aeron.formatLatency(aeron.getP99Nanos()), kafka.formatLatency(kafka.getP99Nanos()));
        System.out.printf("%-20s | %-25s | %-25s%n", "Latency p999",
            aeron.formatLatency(aeron.getP999Nanos()), kafka.formatLatency(kafka.getP999Nanos()));
        System.out.printf("%-20s | %-25s | %-25s%n", "Latency max",
            aeron.formatLatency(aeron.getMaxNanos()), kafka.formatLatency(kafka.getMaxNanos()));
        System.out.println(line);
        System.out.println();
    }
}
