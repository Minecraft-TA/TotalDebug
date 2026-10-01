package com.github.minecraft_ta.totalDebugCompanion.change;

import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindingControl;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindings;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyAssignments;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The project's write queue, which the pipeline writes through and the project finishes before its record closes. */
class WriteQueueTest {
    @TempDir Path directory;
    private GameLocation location;
    private final List<KeyAssignments> assignments = new ArrayList<>();

    @BeforeEach
    void location() {
        this.location = new GameLocation(this.directory);
    }

    @AfterEach
    void closeAssignments() {
        this.assignments.forEach(KeyAssignments::close);
    }

    @Test
    void writesAndClosingDoNotWaitForUnrelatedFileReads() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        WriteQueue empty = new WriteQueue();
        WriteQueue writes = new WriteQueue();
        for (int index = 0; index < 4; index++) {
            Workers.files().execute(() -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture.runAsync(empty::close).get(5, TimeUnit.SECONDS);
            ChangePipeline changes = new ChangePipeline(this.location, ChangeRecord.inMemory(), writes);
            CompletableFuture<String> taken = changes.write(() -> "written");
            CompletableFuture<Void> closing = CompletableFuture.runAsync(writes::close);
            assertEquals("written", taken.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            empty.close();
            writes.close();
        }
    }

    @Test
    void closingFinishesTheWritesAlreadyTakenAndRefusesLaterOnes() throws Exception {
        WriteQueue writes = new WriteQueue();
        ChangePipeline changes = new ChangePipeline(this.location, ChangeRecord.inMemory(), writes);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> taken = changes.write(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "written";
        });

        CompletableFuture<Void> closing = CompletableFuture.runAsync(writes::close);
        release.countDown();
        closing.get(10, TimeUnit.SECONDS);

        assertEquals("written", taken.getNow(null), "a write taken before closing is finished, and so recorded");
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> changes.write(() -> "late").get(5, TimeUnit.SECONDS));
        assertEquals("The project is closing; the change was not written", refused.getCause().getMessage());
    }

    @Test
    void closingWaitsThroughAnInterruptionAndKeepsItForTheCaller() throws Exception {
        WriteQueue writes = new WriteQueue();
        ChangePipeline changes = new ChangePipeline(this.location, ChangeRecord.inMemory(), writes);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> taken = changes.write(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "written";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        boolean[] interruptedAfter = new boolean[1];
        Thread closer = new Thread(() -> {
            writes.close();
            interruptedAfter[0] = Thread.currentThread().isInterrupted();
        });

        closer.start();
        closer.interrupt();
        closer.join(200);
        assertTrue(closer.isAlive(), "an interruption does not end the wait while a write runs");
        release.countDown();
        closer.join(5_000);

        assertEquals("written", taken.getNow(null));
        assertTrue(interruptedAfter[0], "the interruption is kept for the caller");
    }

    @Test
    void anOfflineKeyChangeTakenBeforeClosingIsWrittenAndRecorded() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "key_key.jump:key.keyboard.space\n");
        ChangeRecord record = ChangeRecord.inMemory();
        WriteQueue writes = new WriteQueue();
        ChangePipeline changes = new ChangePipeline(this.location, record, writes);
        KeyBindingControl keys = new KeyBindingControl(changes, assignments());
        CountDownLatch release = new CountDownLatch(1);
        changes.write(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        CompletableFuture<String> key = keys.set(List.of(new KeyBindingControl.Change("key.jump",
                new KeyBindings.Assignment("key.keyboard.space", "NONE"), new KeyBindings.Assignment("key.keyboard.g", "NONE"))));

        CompletableFuture<Void> closing = CompletableFuture.runAsync(writes::close);
        release.countDown();
        closing.get(10, TimeUnit.SECONDS);

        assertTrue(key.isDone());
        assertEquals(List.of("key_key.jump:key.keyboard.g"), Files.readAllLines(options));
        assertEquals(new KeyBindings.Assignment("key.keyboard.space", "NONE"), keys.original("key.jump"),
                "the key change is recorded before the record could close");
    }

    private KeyAssignments assignments() {
        KeyAssignments assignments = new KeyAssignments(this.directory.resolve("options.txt"));
        this.assignments.add(assignments);
        return assignments;
    }
}
