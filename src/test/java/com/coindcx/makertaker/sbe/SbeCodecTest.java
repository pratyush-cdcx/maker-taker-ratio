package com.coindcx.makertaker.sbe;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SbeCodecTest {

    private static final int BUFFER_SIZE = 512;

    // ==================== TradeExecution round-trip ====================

    @Test
    void tradeExecutionRoundTripWithHeader() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(1001L)
            .orderId(2002L)
            .userId(3003L)
            .side(SideEnum.BUY)
            .price(50000_00000000L)
            .quantity(1_00000000L)
            .timestamp(1700000000000L)
            .isMaker(BooleanType.TRUE)
            .symbol("BTC-USDT");

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);

        assertEquals(TradeExecutionEncoder.BLOCK_LENGTH, headerDecoder.blockLength());
        assertEquals(TradeExecutionEncoder.TEMPLATE_ID, headerDecoder.templateId());
        assertEquals(TradeExecutionEncoder.SCHEMA_ID, headerDecoder.schemaId());
        assertEquals(TradeExecutionEncoder.SCHEMA_VERSION, headerDecoder.version());

        assertEquals(1001L, decoder.executionId());
        assertEquals(2002L, decoder.orderId());
        assertEquals(3003L, decoder.userId());
        assertEquals(SideEnum.BUY, decoder.side());
        assertEquals(50000_00000000L, decoder.price());
        assertEquals(1_00000000L, decoder.quantity());
        assertEquals(1700000000000L, decoder.timestamp());
        assertEquals(BooleanType.TRUE, decoder.isMaker());
        assertEquals("BTC-USDT", decoder.symbol());
    }

    @Test
    void tradeExecutionRoundTripSellSideNotMaker() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(5555L)
            .orderId(6666L)
            .userId(7777L)
            .side(SideEnum.SELL)
            .price(100L)
            .quantity(200L)
            .timestamp(1L)
            .isMaker(BooleanType.FALSE)
            .symbol("ETH-BTC");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals(SideEnum.SELL, decoder.side());
        assertEquals(BooleanType.FALSE, decoder.isMaker());
        assertEquals("ETH-BTC", decoder.symbol());
    }

    @Test
    void tradeExecutionWithEmptySymbol() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(1L)
            .orderId(2L)
            .userId(3L)
            .side(SideEnum.BUY)
            .price(0L)
            .quantity(0L)
            .timestamp(0L)
            .isMaker(BooleanType.FALSE)
            .symbol("");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals("", decoder.symbol());
    }

    @Test
    void tradeExecutionWithNullSymbol() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(1L)
            .orderId(2L)
            .userId(3L)
            .side(SideEnum.BUY)
            .price(0L)
            .quantity(0L)
            .timestamp(0L)
            .isMaker(BooleanType.FALSE)
            .symbol(null);

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals("", decoder.symbol());
    }

    @Test
    void tradeExecutionWithLongSymbol() {
        String longSymbol = "SUPERLONGTOKEN-ANOTHERLONGTOKEN";
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(1024));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(1L)
            .orderId(2L)
            .userId(3L)
            .side(SideEnum.SELL)
            .price(999L)
            .quantity(888L)
            .timestamp(777L)
            .isMaker(BooleanType.TRUE)
            .symbol(longSymbol);

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals(longSymbol, decoder.symbol());
    }

    @Test
    void tradeExecutionBoundaryValues() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(Long.MAX_VALUE)
            .orderId(Long.MIN_VALUE + 1)
            .userId(0L)
            .side(SideEnum.BUY)
            .price(Long.MAX_VALUE)
            .quantity(0L)
            .timestamp(Long.MAX_VALUE)
            .isMaker(BooleanType.TRUE)
            .symbol("X");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals(Long.MAX_VALUE, decoder.executionId());
        assertEquals(Long.MIN_VALUE + 1, decoder.orderId());
        assertEquals(0L, decoder.userId());
        assertEquals(Long.MAX_VALUE, decoder.price());
        assertEquals(0L, decoder.quantity());
        assertEquals(Long.MAX_VALUE, decoder.timestamp());
    }

    @Test
    void tradeExecutionDirectWrapWithoutHeader() {
        UnsafeBuffer buffer = allocateBuffer();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();
        encoder.wrap(buffer, 0);
        encoder.executionId(42L)
            .orderId(43L)
            .userId(44L)
            .side(SideEnum.BUY)
            .price(100L)
            .quantity(200L)
            .timestamp(300L)
            .isMaker(BooleanType.FALSE)
            .symbol("A");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrap(buffer, 0, TradeExecutionEncoder.BLOCK_LENGTH, TradeExecutionEncoder.SCHEMA_VERSION);

        assertEquals(42L, decoder.executionId());
        assertEquals(43L, decoder.orderId());
        assertEquals(44L, decoder.userId());
        assertEquals(SideEnum.BUY, decoder.side());
        assertEquals(100L, decoder.price());
        assertEquals(200L, decoder.quantity());
        assertEquals(300L, decoder.timestamp());
        assertEquals(BooleanType.FALSE, decoder.isMaker());
        assertEquals("A", decoder.symbol());
    }

    @Test
    void tradeExecutionEncodedLengthIncludesSymbol() {
        UnsafeBuffer buffer = allocateBuffer();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();
        encoder.wrap(buffer, 0);
        encoder.executionId(1L).orderId(2L).userId(3L)
            .side(SideEnum.BUY).price(4L).quantity(5L).timestamp(6L)
            .isMaker(BooleanType.FALSE)
            .symbol("BTC");

        int encoded = encoder.encodedLength();
        assertEquals(TradeExecutionEncoder.BLOCK_LENGTH + 4 + 3, encoded);
    }

    @Test
    void tradeExecutionBlockLengthConstant() {
        assertEquals(50, TradeExecutionEncoder.BLOCK_LENGTH);
        assertEquals(50, TradeExecutionDecoder.BLOCK_LENGTH);
    }

    @Test
    void tradeExecutionTemplateAndSchemaIds() {
        assertEquals(1, TradeExecutionEncoder.TEMPLATE_ID);
        assertEquals(1, TradeExecutionEncoder.SCHEMA_ID);
        assertEquals(1, TradeExecutionEncoder.SCHEMA_VERSION);
    }

    // ==================== RatioUpdate round-trip ====================

    @Test
    void ratioUpdateRoundTripWithHeader() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(1001L)
            .timestamp(1700000000000L)
            .makerCount1H(100L)
            .takerCount1H(50L)
            .ratio1H(0.6667)
            .makerCount24H(500L)
            .takerCount24H(300L)
            .ratio24H(0.625)
            .makerCount7D(3000L)
            .takerCount7D(2000L)
            .ratio7D(0.6)
            .makerCount30D(10000L)
            .takerCount30D(8000L)
            .ratio30D(0.5556)
            .symbol("BTC-USDT");

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);

        assertEquals(RatioUpdateEncoder.BLOCK_LENGTH, headerDecoder.blockLength());
        assertEquals(RatioUpdateEncoder.TEMPLATE_ID, headerDecoder.templateId());
        assertEquals(RatioUpdateEncoder.SCHEMA_ID, headerDecoder.schemaId());
        assertEquals(RatioUpdateEncoder.SCHEMA_VERSION, headerDecoder.version());

        assertEquals(1001L, decoder.userId());
        assertEquals(1700000000000L, decoder.timestamp());
        assertEquals(100L, decoder.makerCount1H());
        assertEquals(50L, decoder.takerCount1H());
        assertEquals(0.6667, decoder.ratio1H(), 1e-9);
        assertEquals(500L, decoder.makerCount24H());
        assertEquals(300L, decoder.takerCount24H());
        assertEquals(0.625, decoder.ratio24H(), 1e-9);
        assertEquals(3000L, decoder.makerCount7D());
        assertEquals(2000L, decoder.takerCount7D());
        assertEquals(0.6, decoder.ratio7D(), 1e-9);
        assertEquals(10000L, decoder.makerCount30D());
        assertEquals(8000L, decoder.takerCount30D());
        assertEquals(0.5556, decoder.ratio30D(), 1e-9);
        assertEquals("BTC-USDT", decoder.symbol());
    }

    @Test
    void ratioUpdateWithZeroCounts() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(42L)
            .timestamp(0L)
            .makerCount1H(0L).takerCount1H(0L).ratio1H(0.0)
            .makerCount24H(0L).takerCount24H(0L).ratio24H(0.0)
            .makerCount7D(0L).takerCount7D(0L).ratio7D(0.0)
            .makerCount30D(0L).takerCount30D(0L).ratio30D(0.0)
            .symbol("");

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals(42L, decoder.userId());
        assertEquals(0L, decoder.makerCount1H());
        assertEquals(0L, decoder.takerCount1H());
        assertEquals(0.0, decoder.ratio1H(), 1e-15);
        assertEquals("", decoder.symbol());
    }

    @Test
    void ratioUpdateWithMaxValues() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(Long.MAX_VALUE)
            .timestamp(Long.MAX_VALUE)
            .makerCount1H(Long.MAX_VALUE).takerCount1H(Long.MAX_VALUE).ratio1H(Double.MAX_VALUE)
            .makerCount24H(Long.MAX_VALUE).takerCount24H(Long.MAX_VALUE).ratio24H(Double.MAX_VALUE)
            .makerCount7D(Long.MAX_VALUE).takerCount7D(Long.MAX_VALUE).ratio7D(Double.MAX_VALUE)
            .makerCount30D(Long.MAX_VALUE).takerCount30D(Long.MAX_VALUE).ratio30D(Double.MAX_VALUE)
            .symbol("Z");

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals(Long.MAX_VALUE, decoder.userId());
        assertEquals(Long.MAX_VALUE, decoder.timestamp());
        assertEquals(Long.MAX_VALUE, decoder.makerCount1H());
        assertEquals(Double.MAX_VALUE, decoder.ratio1H(), 1e-9);
        assertEquals("Z", decoder.symbol());
    }

    @Test
    void ratioUpdateWithSpecialDoubleValues() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(1L).timestamp(2L)
            .makerCount1H(0L).takerCount1H(0L).ratio1H(Double.NaN)
            .makerCount24H(0L).takerCount24H(0L).ratio24H(Double.POSITIVE_INFINITY)
            .makerCount7D(0L).takerCount7D(0L).ratio7D(Double.NEGATIVE_INFINITY)
            .makerCount30D(0L).takerCount30D(0L).ratio30D(-0.0)
            .symbol("X");

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertTrue(Double.isNaN(decoder.ratio1H()));
        assertEquals(Double.POSITIVE_INFINITY, decoder.ratio24H());
        assertEquals(Double.NEGATIVE_INFINITY, decoder.ratio7D());
        assertEquals(-0.0, decoder.ratio30D());
    }

    @Test
    void ratioUpdateDirectWrapWithoutHeader() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();
        encoder.wrap(buffer, 0);
        encoder.userId(99L).timestamp(100L)
            .makerCount1H(1L).takerCount1H(2L).ratio1H(0.333)
            .makerCount24H(3L).takerCount24H(4L).ratio24H(0.429)
            .makerCount7D(5L).takerCount7D(6L).ratio7D(0.455)
            .makerCount30D(7L).takerCount30D(8L).ratio30D(0.467)
            .symbol("DOGE-USDT");

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrap(buffer, 0, RatioUpdateEncoder.BLOCK_LENGTH, RatioUpdateEncoder.SCHEMA_VERSION);

        assertEquals(99L, decoder.userId());
        assertEquals(100L, decoder.timestamp());
        assertEquals(1L, decoder.makerCount1H());
        assertEquals(2L, decoder.takerCount1H());
        assertEquals(0.333, decoder.ratio1H(), 1e-9);
        assertEquals("DOGE-USDT", decoder.symbol());
    }

    @Test
    void ratioUpdateBlockLengthConstant() {
        assertEquals(112, RatioUpdateEncoder.BLOCK_LENGTH);
        assertEquals(112, RatioUpdateDecoder.BLOCK_LENGTH);
    }

    @Test
    void ratioUpdateTemplateAndSchemaIds() {
        assertEquals(2, RatioUpdateEncoder.TEMPLATE_ID);
        assertEquals(1, RatioUpdateEncoder.SCHEMA_ID);
        assertEquals(1, RatioUpdateEncoder.SCHEMA_VERSION);
    }

    @Test
    void ratioUpdateEncodedLengthIncludesSymbol() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();
        encoder.wrap(buffer, 0);
        encoder.userId(1L).timestamp(2L)
            .makerCount1H(0L).takerCount1H(0L).ratio1H(0.0)
            .makerCount24H(0L).takerCount24H(0L).ratio24H(0.0)
            .makerCount7D(0L).takerCount7D(0L).ratio7D(0.0)
            .makerCount30D(0L).takerCount30D(0L).ratio30D(0.0)
            .symbol("AB");

        int encoded = encoder.encodedLength();
        assertEquals(RatioUpdateEncoder.BLOCK_LENGTH + 4 + 2, encoded);
    }

    // ==================== MessageHeader constants ====================

    @Test
    void messageHeaderEncodedLength() {
        assertEquals(8, MessageHeaderEncoder.ENCODED_LENGTH);
        assertEquals(8, MessageHeaderDecoder.ENCODED_LENGTH);
    }

    // ==================== Enum round-trips ====================

    @ParameterizedTest
    @MethodSource("sideEnumValues")
    void sideEnumRoundTrip(SideEnum side) {
        UnsafeBuffer buffer = allocateBuffer();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();
        encoder.wrap(buffer, 0);
        encoder.executionId(1L).orderId(2L).userId(3L)
            .side(side)
            .price(4L).quantity(5L).timestamp(6L)
            .isMaker(BooleanType.FALSE).symbol("T");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrap(buffer, 0, TradeExecutionEncoder.BLOCK_LENGTH, TradeExecutionEncoder.SCHEMA_VERSION);

        assertEquals(side, decoder.side());
    }

    static Stream<Arguments> sideEnumValues() {
        return Stream.of(
            Arguments.of(SideEnum.BUY),
            Arguments.of(SideEnum.SELL)
        );
    }

    @ParameterizedTest
    @MethodSource("booleanTypeValues")
    void booleanTypeRoundTrip(BooleanType val) {
        UnsafeBuffer buffer = allocateBuffer();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();
        encoder.wrap(buffer, 0);
        encoder.executionId(1L).orderId(2L).userId(3L)
            .side(SideEnum.BUY)
            .price(4L).quantity(5L).timestamp(6L)
            .isMaker(val).symbol("T");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrap(buffer, 0, TradeExecutionEncoder.BLOCK_LENGTH, TradeExecutionEncoder.SCHEMA_VERSION);

        assertEquals(val, decoder.isMaker());
    }

    static Stream<Arguments> booleanTypeValues() {
        return Stream.of(
            Arguments.of(BooleanType.TRUE),
            Arguments.of(BooleanType.FALSE)
        );
    }

    // ==================== wrapAndApplyHeader template ID check ====================

    @Test
    void decoderRejectsWrongTemplateId() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        headerEncoder.wrap(buffer, 0)
            .blockLength(TradeExecutionEncoder.BLOCK_LENGTH)
            .templateId(999)
            .schemaId(1)
            .version(1);

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        assertThrows(IllegalStateException.class,
            () -> decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder()));
    }

    @Test
    void ratioDecoderRejectsWrongTemplateId() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        headerEncoder.wrap(buffer, 0)
            .blockLength(RatioUpdateEncoder.BLOCK_LENGTH)
            .templateId(999)
            .schemaId(1)
            .version(1);

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        assertThrows(IllegalStateException.class,
            () -> decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder()));
    }

    // ==================== Multiple messages in same buffer ====================

    @Test
    void twoTradeExecutionMessagesInSequence() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(1024));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(1L).orderId(10L).userId(100L)
            .side(SideEnum.BUY).price(50L).quantity(5L).timestamp(1000L)
            .isMaker(BooleanType.TRUE).symbol("AAA");

        int firstMsgLength = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();

        encoder.wrapAndApplyHeader(buffer, firstMsgLength, headerEncoder);
        encoder.executionId(2L).orderId(20L).userId(200L)
            .side(SideEnum.SELL).price(60L).quantity(6L).timestamp(2000L)
            .isMaker(BooleanType.FALSE).symbol("BBB");

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        TradeExecutionDecoder decoder = new TradeExecutionDecoder();

        decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertEquals(1L, decoder.executionId());
        assertEquals("AAA", decoder.symbol());

        decoder.wrapAndApplyHeader(buffer, firstMsgLength, headerDecoder);
        assertEquals(2L, decoder.executionId());
        assertEquals("BBB", decoder.symbol());
    }

    // ==================== Non-zero offset ====================

    @Test
    void tradeExecutionAtNonZeroOffset() {
        int baseOffset = 64;
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(BUFFER_SIZE + baseOffset));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, baseOffset, headerEncoder);
        encoder.executionId(77L).orderId(88L).userId(99L)
            .side(SideEnum.SELL).price(111L).quantity(222L).timestamp(333L)
            .isMaker(BooleanType.TRUE).symbol("OFF");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, baseOffset, new MessageHeaderDecoder());

        assertEquals(77L, decoder.executionId());
        assertEquals(99L, decoder.userId());
        assertEquals("OFF", decoder.symbol());
    }

    // ==================== UTF-8 symbol ====================

    @Test
    void tradeExecutionWithUnicodeSymbol() {
        String unicodeSymbol = "BTC-\u00FC\u00E9";
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(1L).orderId(2L).userId(3L)
            .side(SideEnum.BUY).price(4L).quantity(5L).timestamp(6L)
            .isMaker(BooleanType.FALSE).symbol(unicodeSymbol);

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());

        assertEquals(unicodeSymbol, decoder.symbol());
    }

    // ==================== sbeRewind ====================

    @Test
    void tradeExecutionSbeRewindAllowsReRead() {
        UnsafeBuffer buffer = allocateBuffer();
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.executionId(10L).orderId(20L).userId(30L)
            .side(SideEnum.BUY).price(40L).quantity(50L).timestamp(60L)
            .isMaker(BooleanType.TRUE).symbol("RW");

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());
        assertEquals("RW", decoder.symbol());

        decoder.sbeRewind();
        assertEquals(10L, decoder.executionId());
        assertEquals("RW", decoder.symbol());
    }

    @Test
    void ratioUpdateSbeRewindAllowsReRead() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(11L).timestamp(22L)
            .makerCount1H(1L).takerCount1H(2L).ratio1H(0.5)
            .makerCount24H(3L).takerCount24H(4L).ratio24H(0.6)
            .makerCount7D(5L).takerCount7D(6L).ratio7D(0.7)
            .makerCount30D(7L).takerCount30D(8L).ratio30D(0.8)
            .symbol("RW2");

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());
        assertEquals("RW2", decoder.symbol());

        decoder.sbeRewind();
        assertEquals(11L, decoder.userId());
        assertEquals("RW2", decoder.symbol());
    }

    // ==================== putSymbol(byte[], offset, length) ====================

    @Test
    void tradeExecutionPutSymbolByteArray() {
        UnsafeBuffer buffer = allocateBuffer();
        TradeExecutionEncoder encoder = new TradeExecutionEncoder();
        encoder.wrap(buffer, 0);
        encoder.executionId(1L).orderId(2L).userId(3L)
            .side(SideEnum.BUY).price(4L).quantity(5L).timestamp(6L)
            .isMaker(BooleanType.FALSE);

        byte[] symbolBytes = "XRP-USDT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        encoder.putSymbol(symbolBytes, 0, symbolBytes.length);

        TradeExecutionDecoder decoder = new TradeExecutionDecoder();
        decoder.wrap(buffer, 0, TradeExecutionEncoder.BLOCK_LENGTH, TradeExecutionEncoder.SCHEMA_VERSION);

        assertEquals("XRP-USDT", decoder.symbol());
    }

    @Test
    void ratioUpdatePutSymbolByteArray() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        RatioUpdateEncoder encoder = new RatioUpdateEncoder();
        encoder.wrap(buffer, 0);
        encoder.userId(1L).timestamp(2L)
            .makerCount1H(0L).takerCount1H(0L).ratio1H(0.0)
            .makerCount24H(0L).takerCount24H(0L).ratio24H(0.0)
            .makerCount7D(0L).takerCount7D(0L).ratio7D(0.0)
            .makerCount30D(0L).takerCount30D(0L).ratio30D(0.0);

        byte[] symbolBytes = "SOL-USDT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        encoder.putSymbol(symbolBytes, 0, symbolBytes.length);

        RatioUpdateDecoder decoder = new RatioUpdateDecoder();
        decoder.wrap(buffer, 0, RatioUpdateEncoder.BLOCK_LENGTH, RatioUpdateEncoder.SCHEMA_VERSION);

        assertEquals("SOL-USDT", decoder.symbol());
    }

    // ==================== helpers ====================

    private static UnsafeBuffer allocateBuffer() {
        return new UnsafeBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    }
}
