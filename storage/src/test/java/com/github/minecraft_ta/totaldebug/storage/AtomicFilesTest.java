package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir Path root;

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
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
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
