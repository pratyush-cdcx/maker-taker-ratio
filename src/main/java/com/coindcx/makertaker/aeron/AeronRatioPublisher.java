package com.coindcx.makertaker.aeron;

import com.coindcx.makertaker.model.UserSymbolState;
import com.coindcx.makertaker.model.UserSymbolState.WindowSnapshot;
import com.coindcx.makertaker.model.TimeWindow;
import com.coindcx.makertaker.sbe.MessageHeaderEncoder;
import com.coindcx.makertaker.sbe.RatioUpdateEncoder;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public final class AeronRatioPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronRatioPublisher.class);
    private static final int BUFFER_SIZE = 512;

    private final ExclusivePublication publication;
    private final UnsafeBuffer buffer;
    private final MessageHeaderEncoder headerEncoder;
    private final RatioUpdateEncoder encoder;

    public AeronRatioPublisher(ExclusivePublication publication) {
        this.publication = publication;
        this.buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
        this.headerEncoder = new MessageHeaderEncoder();
        this.encoder = new RatioUpdateEncoder();
    }

    /**
     * Zero-allocation publish: reads state under the visitor lock and encodes
     * directly into the pre-allocated SBE buffer without creating intermediate
     * arrays or snapshot records.
     */
    public boolean publishDirect(UserSymbolState state) {
        state.visit((userId, symbol, timestamp,
                     maker1H, taker1H, ratio1H,
                     maker24H, taker24H, ratio24H,
                     maker7D, taker7D, ratio7D,
                     maker30D, taker30D, ratio30D) -> {
            encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
            encoder.userId(userId);
            encoder.timestamp(timestamp);
            encoder.makerCount1H(maker1H);
            encoder.takerCount1H(taker1H);
            encoder.ratio1H(ratio1H);
            encoder.makerCount24H(maker24H);
            encoder.takerCount24H(taker24H);
            encoder.ratio24H(ratio24H);
            encoder.makerCount7D(maker7D);
            encoder.takerCount7D(taker7D);
            encoder.ratio7D(ratio7D);
            encoder.makerCount30D(maker30D);
            encoder.takerCount30D(taker30D);
            encoder.ratio30D(ratio30D);
            encoder.symbol(symbol != null ? symbol : "");
        });

        int length = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
        return offerWithRetry(length);
    }

    /**
     * Snapshot-based publish for backward compatibility (used by Kafka path
     * and tests).
     */
    public boolean publish(WindowSnapshot snapshot) {
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);

        int h1 = TimeWindow.HOUR_1.ordinal();
        int h24 = TimeWindow.HOUR_24.ordinal();
        int d7 = TimeWindow.DAY_7.ordinal();
        int d30 = TimeWindow.DAY_30.ordinal();

        encoder.userId(snapshot.userId());
        encoder.timestamp(snapshot.lastUpdatedTimestamp());
        encoder.makerCount1H(snapshot.makerCounts()[h1]);
        encoder.takerCount1H(snapshot.takerCounts()[h1]);
        encoder.ratio1H(snapshot.ratios()[h1]);
        encoder.makerCount24H(snapshot.makerCounts()[h24]);
        encoder.takerCount24H(snapshot.takerCounts()[h24]);
        encoder.ratio24H(snapshot.ratios()[h24]);
        encoder.makerCount7D(snapshot.makerCounts()[d7]);
        encoder.takerCount7D(snapshot.takerCounts()[d7]);
        encoder.ratio7D(snapshot.ratios()[d7]);
        encoder.makerCount30D(snapshot.makerCounts()[d30]);
        encoder.takerCount30D(snapshot.takerCounts()[d30]);
        encoder.ratio30D(snapshot.ratios()[d30]);

        String symbol = snapshot.symbol() != null ? snapshot.symbol() : "";
        encoder.symbol(symbol);

        int length = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
        return offerWithRetry(length);
    }

    private boolean offerWithRetry(int length) {
        long result = publication.offer(buffer, 0, length);
        if (result < 0) {
            if (result == Publication.BACK_PRESSURED) {
                log.debug("Aeron back-pressured on ratio publish");
            } else if (result == Publication.NOT_CONNECTED) {
                log.debug("Aeron not connected for ratio publish");
            } else if (result == Publication.ADMIN_ACTION) {
                result = publication.offer(buffer, 0, length);
            }
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        if (publication != null && !publication.isClosed()) {
            publication.close();
        }
    }
}
