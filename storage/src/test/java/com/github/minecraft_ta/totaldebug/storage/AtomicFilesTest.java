package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir Path root;

    @Test @EnabledOnOs(OS.WINDOWS)
    void descriptorPublicationWaitsForConcurrentDiscoveryReader() throws Exception {
        Path target = root.resolve("instance.properties");
        var previous = new CompanionSessionDescriptor(16, 1234, 1, 1235, null);
        var next = new CompanionSessionDescriptor(16, 1234, 1, 1235, "selected");
        previous.writeAtomically(target);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var reader = Files.newBufferedReader(target);
            try {
                var publication = worker.submit(() -> { next.writeAtomically(target); return null; });
                assertThrows(TimeoutException.class, () -> publication.get(150, TimeUnit.MILLISECONDS));
                assertEquals(previous, CompanionSessionDescriptor.read(target, 16));
                reader.close();
                publication.get(3, TimeUnit.SECONDS);
                assertEquals(next, CompanionSessionDescriptor.read(target, 16));
            } finally { reader.close(); }
        }
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void permanentlyBlockedReplacementFailsAndPreservesPreviousFile() throws Exception {
        Path target = Files.writeString(root.resolve("state.json"), "previous");
        var writes = new AtomicInteger();
        try (var reader = Files.newBufferedReader(target)) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(AccessDeniedException.class, () -> AtomicFiles.replace(target, staged -> {
                        writes.incrementAndGet();
                        Files.writeString(staged, "new");
                    })));
            assertEquals("previous", reader.readLine());
        }
        assertEquals(1, writes.get(), "Retry only the move; never rerun the writer");
        assertEquals("previous", Files.readString(target));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void interruptedReplacementStopsRetryingAndPreservesPreviousFile() throws Exception {
        Path target = Files.writeString(root.resolve("state.json"), "previous");
        try (var reader = Files.newBufferedReader(target)) {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> AtomicFiles.writeString(target, "new"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("previous", Files.readString(target));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void failedReplacementPreservesPreviousFileAndRemovesStaging() throws IOException {
        Path target = root.resolve("state.json");
        Files.writeString(target, "previous");
        assertThrows(IOException.class, () -> AtomicFiles.replace(target, staged -> {
            Files.writeString(staged, "partial");
            throw new IOException("injected");
        }));
        assertEquals("previous", Files.readString(target));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
        AtomicFiles.writeString(target, "complete");
        assertEquals("complete", Files.readString(target));
    }

    @Test void newFilePublicationNeverOverwritesAnExistingScript() throws IOException {
        Path file = root.resolve("Example.tdscript");
        AtomicFiles.createNewString(file, "return 1;");
        assertThrows(FileAlreadyExistsException.class,
                () -> AtomicFiles.createNewString(file, "return 2;"));
        assertEquals("return 1;", Files.readString(file));
        try (var children = Files.list(root)) {
            assertEquals(1, children.count());
        }
    }

    @Test void directoryPublicationNeverDeletesAnExistingEntry() throws IOException {
        Path target = root.resolve("entry");
        AtomicFiles.publishDirectory(target, staged -> Files.writeString(staged.resolve("source.java"), "original"));
        assertThrows(IOException.class, () -> AtomicFiles.publishDirectory(target,
                staged -> Files.writeString(staged.resolve("source.java"), "replacement")));
        assertEquals("original", Files.readString(target.resolve("source.java")));
        try (var files = Files.list(root)) {
            assertEquals(1, files.count());
        }
    }

    @Test void deletionCannotTargetItsRootOrSibling() throws IOException {
        Path owned = Files.createDirectory(root.resolve("cache"));
        Path sibling = Files.writeString(root.resolve("script.tdscript"), "valuable");
        assertThrows(IOException.class, () -> AtomicFiles.deleteOwned(owned, owned));
        assertThrows(IOException.class, () -> AtomicFiles.deleteOwned(owned, sibling));
        assertTrue(Files.exists(sibling));
    }

    @Test void abandonedStagingCleanupPreservesActiveAndUnrecognizedFiles() throws IOException {
        Path abandoned = Files.writeString(root.resolve(".td-9223372036854775807-test.tmp"), "");
        Path active = Files.writeString(root.resolve(".td-" + ProcessHandle.current().pid() + "-test.tmp"), "");
        Path unrelated = Files.writeString(root.resolve("user.tmp"), "");
        AtomicFiles.cleanupAbandonedStaging(root);
        assertFalse(Files.exists(abandoned));
        assertTrue(Files.exists(active));
        assertTrue(Files.exists(unrelated));
    }
}
