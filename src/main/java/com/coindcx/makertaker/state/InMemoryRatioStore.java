package com.coindcx.makertaker.state;

import com.coindcx.makertaker.model.RatioKey;
import com.coindcx.makertaker.model.UserSymbolState;

import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRatioStore {
    private final ConcurrentHashMap<Long, UserSymbolState> overallStore;
    private final ConcurrentHashMap<RatioKey, UserSymbolState> perSymbolStore;

    public InMemoryRatioStore() {
        this.overallStore = new ConcurrentHashMap<>(16384);
        this.perSymbolStore = new ConcurrentHashMap<>(65536);
    }

    public UserSymbolState getOrCreateOverall(long userId) {
        return overallStore.computeIfAbsent(userId,
            id -> new UserSymbolState(id, null));
    }

    public UserSymbolState getOrCreatePerSymbol(long userId, String symbol) {
        RatioKey key = new RatioKey(userId, symbol);
        return perSymbolStore.computeIfAbsent(key,
            k -> new UserSymbolState(k.userId(), k.symbol()));
    }

    public UserSymbolState getOverall(long userId) {
        return overallStore.get(userId);
    }

    public UserSymbolState getPerSymbol(long userId, String symbol) {
        return perSymbolStore.get(new RatioKey(userId, symbol));
    }

    public int overallSize() {
        return overallStore.size();
    }

    public int perSymbolSize() {
        return perSymbolStore.size();
    }
}
