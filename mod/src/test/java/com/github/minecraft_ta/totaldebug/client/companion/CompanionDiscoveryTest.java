package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class CompanionDiscoveryTest {
    @TempDir Path root;

    @Test void quietDirectoryAndUnrelatedFilesDoNotCauseDiscoveryAttempts() throws Exception {
        var attempts = new AtomicInteger();
        try (var discovery = new CompanionDiscovery(root, () -> {
            attempts.incrementAndGet();
            return CompanionDiscovery.Result.IDLE;
        }, () -> false, () -> true, () -> { })) {
            discovery.start();
            await(() -> attempts.get() == 1);
            Files.writeString(root.resolve("mcp-endpoint.json"), "unrelated");
            Thread.sleep(1200);
            assertEquals(1, attempts.get());
            AtomicFiles.writeString(root.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME), "new endpoint");
            await(() -> attempts.get() == 2);
            Thread.sleep(200);
            assertEquals(2, attempts.get());
        }
    }

    @Test void reRegistersADeletedDirectoryAndStopsAfterClose() throws Exception {
        Path directory = Files.createDirectory(root.resolve("discovery"));
        var attempts = new AtomicInteger();
        var discovery = new CompanionDiscovery(directory, () -> {
            attempts.incrementAndGet();
            return CompanionDiscovery.Result.IDLE;
        }, () -> false, () -> true, () -> { });
        try {
            discovery.start();
            await(() -> attempts.get() == 1);
            Files.delete(directory);
            await(() -> attempts.get() >= 2);
            assertTrue(Files.isDirectory(directory));
        } finally { discovery.close(); }
        int stopped = attempts.get();
        AtomicFiles.writeString(directory.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME), "after close");
        Thread.sleep(200);
        assertEquals(stopped, attempts.get());
    }

    @Test void relevantWatchEventRechecksPublicationEvenWhenMetadataIsUnchanged() throws Exception {
        Path descriptor = Files.writeString(root.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME), "old");
        var modified = Files.getLastModifiedTime(descriptor);
        var initial = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (var discovery = new CompanionDiscovery(root, () -> {
            if (attempts.incrementAndGet() == 1) {
                initial.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            return CompanionDiscovery.Result.REJECTED;
        }, () -> false, () -> true, () -> { })) {
            discovery.start();
            assertTrue(initial.await(5, TimeUnit.SECONDS));
            Files.writeString(descriptor, "new");
            Files.setLastModifiedTime(descriptor, modified);
            release.countDown();
            await(() -> attempts.get() >= 2);
        } finally { release.countDown(); }
    }

    @Test void connectionLostAsAnAttemptReturnsStillTriggersRecovery() throws Exception {
        var attempts = new AtomicInteger();
        var connected = new AtomicBoolean();
        try (var discovery = new CompanionDiscovery(root, () -> {
            if (attempts.incrementAndGet() > 1) connected.set(true);
            return CompanionDiscovery.Result.CONNECTED;
        }, connected::get, () -> true, () -> { })) {
            discovery.start();
            await(() -> attempts.get() >= 2);
            assertTrue(connected.get());
        }
    }

    @Test void disablingDuringWatchReplacementCleansUpAndReenablingResumesDiscovery() throws Exception {
        Path watched = Files.createDirectory(root.resolve("replace"));
        var enabled = new AtomicBoolean(true);
        var connected = new AtomicBoolean(true);
        var attempts = new AtomicInteger();
        var cleanups = new AtomicInteger();
        try (var discovery = new CompanionDiscovery(watched, () -> {
            attempts.incrementAndGet();
            connected.set(true);
            return CompanionDiscovery.Result.CONNECTED;
        }, connected::get, enabled::get, () -> {
            if (connected.getAndSet(false)) cleanups.incrementAndGet();
        })) {
            discovery.start();
            await(() -> attempts.get() == 1);
            enabled.set(false);
            Files.delete(watched);
            await(() -> cleanups.get() == 1 && Files.isDirectory(watched));
            assertEquals(1, attempts.get());
            assertFalse(connected.get());
            enabled.set(true);
            await(() -> attempts.get() == 2);
            assertTrue(connected.get());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
