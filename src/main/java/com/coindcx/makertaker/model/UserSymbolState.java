package com.coindcx.makertaker.model;

public final class UserSymbolState {
    private final long userId;
    private final String symbol;
    private final WindowState[] windows;
    private volatile long lastUpdatedTimestamp;

    public UserSymbolState(long userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
        TimeWindow[] tw = TimeWindow.values();
        this.windows = new WindowState[tw.length];
        for (int i = 0; i < tw.length; i++) {
            windows[i] = new WindowState(tw[i]);
        }
    }

    public synchronized void record(long timestampMs, boolean isMaker) {
        for (WindowState ws : windows) {
            ws.record(timestampMs, isMaker);
        }
        this.lastUpdatedTimestamp = timestampMs;
    }

    public synchronized void expireOldBuckets(long currentTimeMs) {
        for (WindowState ws : windows) {
            ws.expireOldBuckets(currentTimeMs);
        }
    }

    public synchronized double getRatio(TimeWindow window) {
        return windows[window.ordinal()].getRatio();
    }

    public synchronized long getMakerCount(TimeWindow window) {
        return windows[window.ordinal()].getMakerCount();
    }

    public synchronized long getTakerCount(TimeWindow window) {
        return windows[window.ordinal()].getTakerCount();
    }

    public synchronized WindowSnapshot snapshot() {
        long[] makerCounts = new long[windows.length];
        long[] takerCounts = new long[windows.length];
        double[] ratios = new double[windows.length];
        for (int i = 0; i < windows.length; i++) {
            makerCounts[i] = windows[i].getMakerCount();
            takerCounts[i] = windows[i].getTakerCount();
            ratios[i] = windows[i].getRatio();
        }
        return new WindowSnapshot(userId, symbol, makerCounts, takerCounts, ratios, lastUpdatedTimestamp);
    }

    /**
     * Zero-allocation visitor: reads state under the synchronized lock
     * and passes raw values directly to the visitor without creating
     * intermediate arrays or records.
     */
    public synchronized void visit(RatioVisitor visitor) {
        int h1 = TimeWindow.HOUR_1.ordinal();
        int h24 = TimeWindow.HOUR_24.ordinal();
        int d7 = TimeWindow.DAY_7.ordinal();
        int d30 = TimeWindow.DAY_30.ordinal();

        visitor.accept(
            userId, symbol, lastUpdatedTimestamp,
            windows[h1].getMakerCount(), windows[h1].getTakerCount(), windows[h1].getRatio(),
            windows[h24].getMakerCount(), windows[h24].getTakerCount(), windows[h24].getRatio(),
            windows[d7].getMakerCount(), windows[d7].getTakerCount(), windows[d7].getRatio(),
            windows[d30].getMakerCount(), windows[d30].getTakerCount(), windows[d30].getRatio()
        );
    }

    public long getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public long getLastUpdatedTimestamp() {
        return lastUpdatedTimestamp;
    }

    @FunctionalInterface
    public interface RatioVisitor {
        void accept(long userId, String symbol, long timestamp,
                    long maker1H, long taker1H, double ratio1H,
                    long maker24H, long taker24H, double ratio24H,
                    long maker7D, long taker7D, double ratio7D,
                    long maker30D, long taker30D, double ratio30D);
    }

    public record WindowSnapshot(
        long userId,
        String symbol,
        long[] makerCounts,
        long[] takerCounts,
        double[] ratios,
        long lastUpdatedTimestamp
    ) {}
}
