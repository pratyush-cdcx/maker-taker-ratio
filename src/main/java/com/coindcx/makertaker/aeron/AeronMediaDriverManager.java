package com.coindcx.makertaker.aeron;

import com.coindcx.makertaker.config.AppConfig;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AeronMediaDriverManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronMediaDriverManager.class);

    private final MediaDriver mediaDriver;
    private final Aeron aeron;

    public AeronMediaDriverManager(AppConfig config) {
        log.info("Starting embedded Aeron media driver at: {}", config.getAeronDir());

        MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .aeronDirectoryName(config.getAeronDir())
            .threadingMode(ThreadingMode.SHARED)
            .sharedIdleStrategy(new BusySpinIdleStrategy());

        this.mediaDriver = MediaDriver.launchEmbedded(driverCtx);

        Aeron.Context aeronCtx = new Aeron.Context()
            .aeronDirectoryName(mediaDriver.aeronDirectoryName());

        this.aeron = Aeron.connect(aeronCtx);
        log.info("Aeron media driver started, directory: {}", mediaDriver.aeronDirectoryName());
    }

    public AeronMediaDriverManager(MediaDriver mediaDriver, Aeron aeron) {
        this.mediaDriver = mediaDriver;
        this.aeron = aeron;
    }

    public Aeron aeron() {
        return aeron;
    }

    public MediaDriver mediaDriver() {
        return mediaDriver;
    }

    @Override
    public void close() {
        log.info("Closing Aeron media driver");
        try {
            aeron.close();
        } catch (Exception e) {
            log.warn("Error closing Aeron client: {}", e.getMessage());
        }
        try {
            mediaDriver.close();
        } catch (Exception e) {
            log.warn("Error closing media driver: {}", e.getMessage());
        }
    }
}
