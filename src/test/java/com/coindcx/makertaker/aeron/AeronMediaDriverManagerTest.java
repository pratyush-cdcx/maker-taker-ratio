package com.coindcx.makertaker.aeron;

import com.coindcx.makertaker.config.AppConfig;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AeronMediaDriverManagerTest {

    @Test
    void constructsWithConfigAndExposesAeronAndMediaDriver(@TempDir Path tempDir) {
        AppConfig config = AppConfig.defaults();
        config.setAeronDir(tempDir.resolve("aeron-test").toString());

        AeronMediaDriverManager manager = new AeronMediaDriverManager(config);
        try {
            assertNotNull(manager.aeron());
            assertNotNull(manager.mediaDriver());
            assertFalse(manager.aeron().isClosed());
        } finally {
            manager.close();
        }
    }

    @Test
    void closeIsIdempotent(@TempDir Path tempDir) {
        AppConfig config = AppConfig.defaults();
        config.setAeronDir(tempDir.resolve("aeron-test2").toString());

        AeronMediaDriverManager manager = new AeronMediaDriverManager(config);
        manager.close();
        assertDoesNotThrow(manager::close);
    }

    @Test
    void constructWithExplicitDriverAndAeron(@TempDir Path tempDir) {
        AppConfig config = AppConfig.defaults();
        config.setAeronDir(tempDir.resolve("aeron-test3").toString());

        AeronMediaDriverManager first = new AeronMediaDriverManager(config);
        try {
            MediaDriver driver = first.mediaDriver();
            Aeron aeron = first.aeron();

            AeronMediaDriverManager second = new AeronMediaDriverManager(driver, aeron);
            assertSame(driver, second.mediaDriver());
            assertSame(aeron, second.aeron());
        } finally {
            first.close();
        }
    }
}
