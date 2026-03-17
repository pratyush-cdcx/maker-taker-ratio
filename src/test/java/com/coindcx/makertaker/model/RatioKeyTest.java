package com.coindcx.makertaker.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RatioKeyTest {

    @Test
    void constructorSetsFields() {
        RatioKey key = new RatioKey(42L, "BTCUSDT");
        assertEquals(42L, key.userId());
        assertEquals("BTCUSDT", key.symbol());
    }

    @Test
    void constructorRejectsNullSymbol() {
        NullPointerException ex = assertThrows(
            NullPointerException.class,
            () -> new RatioKey(1L, null)
        );
        assertEquals("symbol must not be null", ex.getMessage());
    }

    @Test
    void equalityBetweenIdenticalKeys() {
        RatioKey a = new RatioKey(1L, "ETHUSDT");
        RatioKey b = new RatioKey(1L, "ETHUSDT");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityWhenUserIdDiffers() {
        RatioKey a = new RatioKey(1L, "ETHUSDT");
        RatioKey b = new RatioKey(2L, "ETHUSDT");
        assertNotEquals(a, b);
    }

    @Test
    void inequalityWhenSymbolDiffers() {
        RatioKey a = new RatioKey(1L, "ETHUSDT");
        RatioKey b = new RatioKey(1L, "BTCUSDT");
        assertNotEquals(a, b);
    }

    @Test
    void notEqualToNull() {
        RatioKey key = new RatioKey(1L, "BTCUSDT");
        assertNotEquals(null, key);
    }

    @Test
    void notEqualToDifferentType() {
        RatioKey key = new RatioKey(1L, "BTCUSDT");
        assertNotEquals("not a RatioKey", key);
    }

    @Test
    void toStringContainsFieldValues() {
        RatioKey key = new RatioKey(99L, "SOLUSDT");
        String str = key.toString();
        assertTrue(str.contains("99"));
        assertTrue(str.contains("SOLUSDT"));
    }

    @Test
    void zeroUserIdIsAllowed() {
        RatioKey key = new RatioKey(0L, "BTCUSDT");
        assertEquals(0L, key.userId());
    }

    @Test
    void negativeUserIdIsAllowed() {
        RatioKey key = new RatioKey(-1L, "BTCUSDT");
        assertEquals(-1L, key.userId());
    }

    @Test
    void emptySymbolIsAllowed() {
        RatioKey key = new RatioKey(1L, "");
        assertEquals("", key.symbol());
    }

    @Test
    void hashCodeConsistentAcrossInvocations() {
        RatioKey key = new RatioKey(7L, "XRPUSDT");
        int hash1 = key.hashCode();
        int hash2 = key.hashCode();
        assertEquals(hash1, hash2);
    }
}
