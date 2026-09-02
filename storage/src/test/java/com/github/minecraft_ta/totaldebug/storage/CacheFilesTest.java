package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CacheFilesTest {
    @Test
    void replacementWaitsForAReaderAndReleasesTheLockAfterFailure(@TempDir Path directory) throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var replaced = new CountDownLatch(1);
        var reader = CompletableFuture.runAsync(() -> {
            try {
                CacheFiles.locked(directory, () -> {
                    reading.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                });
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        var writer = CompletableFuture.runAsync(() -> {
            writerStarted.countDown();
            try {
                CacheFiles.locked(directory, () -> {
                    replaced.countDown();
                    throw new java.io.IOException("failed publication");
                });
            } catch (java.io.IOException expected) {
                assertEquals("failed publication", expected.getMessage());
            }
        });
        try {
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertFalse(replaced.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        reader.get(5, TimeUnit.SECONDS);
        writer.get(5, TimeUnit.SECONDS);
        assertEquals("available", CacheFiles.locked(directory, () -> "available"));
    }

    @Test
    void namesArePortableReadableAndCaseInsensitive() {
        var used = new HashSet<String>();
        assertEquals("Blocks", CacheNames.uniqueStem("Blocks", used));
        assertEquals("blocks-2", CacheNames.uniqueStem("blocks", used));
        assertEquals("_CON", CacheNames.uniqueStem("CON", used));
        assertEquals("_AUX.txt", CacheNames.uniqueStem("AUX.txt", used));
        assertEquals("_", CacheNames.uniqueStem("..", used));
        String longName = CacheNames.uniqueStem("prefix".repeat(40) + ".RecognizableClass", used);
        assertTrue(longName.endsWith(".RecognizableClass"));
        assertEquals(120, longName.length());
        assertThrows(IllegalArgumentException.class, () -> CacheNames.requireFileName("../outside"));
    }
}
