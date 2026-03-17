package com.coindcx.makertaker.model;

import java.util.Objects;

public record RatioKey(long userId, String symbol) {

    public RatioKey {
        Objects.requireNonNull(symbol, "symbol must not be null");
    }
}
