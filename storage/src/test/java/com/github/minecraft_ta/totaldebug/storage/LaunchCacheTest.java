package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import static org.junit.jupiter.api.Assertions.*;

class LaunchCacheTest {
    @TempDir Path home;

    @Test
    void keepsActiveBuildsAndReclaimsOnlyUnpinnedOldEntries() throws Exception {
        AppPaths paths = new AppPaths(this.home.resolve("app"));
        Path source = Files.write(this.home.resolve("source.jar"), new byte[]{0});
        Path first;
        try (var launching = LaunchCache.stage(paths, source)) {
            first = launching.path();
            try (var running = LaunchCache.pinRunning(paths, first)) {
                assertNotNull(running);
                for (int i = 1; i <= 5; i++) {
                    Files.write(source, new byte[]{(byte) i});
                    try (var lease = LaunchCache.stage(paths, source)) {
                        assertTrue(Files.isRegularFile(lease.path()));
                    }
                    Files.setLastModifiedTime(first.getParent(), FileTime.fromMillis(1));
                }
                assertTrue(Files.isRegularFile(first));
            }
        }
        Files.write(source, new byte[]{6});
        try (var ignored = LaunchCache.stage(paths, source)) {
            assertFalse(Files.exists(first));
            try (var entries = Files.list(paths.launchCache())) {
                assertEquals(3, entries.filter(Files::isDirectory).count());
            }
        }
        assertArrayEquals(new byte[]{6}, Files.readAllBytes(source));
    }
}
