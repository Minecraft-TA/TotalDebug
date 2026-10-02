package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A save waits for no disk, and the newest snapshot is the one left on disk (docs/LAST_DIFFERENCES.md, part 1). */
class JsonStateWriterTest {
    private static final Duration PROMPTLY = Duration.ofSeconds(5);

    @TempDir Path directory;

    @Test
    void aWriteUnderWayHoldsBackNeitherSchedulingNorTheRecordsReaders() throws Exception {
        PausedWrite write = new PausedWrite();
        ChangeRecord record = new ChangeRecord(new JsonStateWriter(file(), write), Clock.systemUTC(), null);
        record.changed(new ChangeRecord.KeyBinding("key.jump"), "key.keyboard.space", "key.keyboard.j");
        CompletableFuture<Void> saving = CompletableFuture.runAsync(() -> {
            try {
                record.saveNow();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        write.awaitPaused();

        assertTimeoutPreemptively(PROMPTLY, () -> {
            record.changed(new ChangeRecord.KeyBinding("key.sneak"), "key.keyboard.left.shift", "key.keyboard.c");
            assertEquals(2, record.size());
        }, "a change and a reader of the record do not wait for the file being written");

        write.release();
        saving.get(5, TimeUnit.SECONDS);
        record.saveNow();
        assertEquals(2, JsonFiles.read(file()).getAsJsonArray("changes").size());
    }

    @Test
    void savesComingDueDuringASlowWriteHoldNoFileWorkers() throws Exception {
        PausedWrite write = new PausedWrite();
        JsonStateWriter writer = new JsonStateWriter(file(), write);
        writer.schedule(value("A"));
        write.awaitPaused();
        // Each comes due while the first write is paused.
        for (String value : List.of("B", "C", "D", "E")) {
            writer.schedule(value(value));
            Thread.sleep(700);
        }

        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> { }, Workers.files());
        assertTimeoutPreemptively(PROMPTLY, () -> other.get(), "other file work still runs");

        write.release();
        writer.close();
        assertEquals("E", read());
    }

    @Test
    void aFlushWritesTheNewestSnapshotAfterAnOlderWriteUnderWay() throws Exception {
        PausedWrite write = new PausedWrite();
        JsonStateWriter writer = new JsonStateWriter(file(), write);
        writer.schedule(value("A"));
        CompletableFuture<Void> first = flushing(writer);
        write.awaitPaused();
        writer.schedule(value("B"));
        CompletableFuture<Void> second = flushing(writer);

        write.release();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertEquals("B", read());
        assertEquals("B", write.written.getLast(), "no older snapshot lands after the newest");
    }

    @Test
    void aFailedWriteLeavesTheNewerSnapshotToBeWritten() throws Exception {
        PausedWrite write = new PausedWrite();
        write.failFirst.set(true);
        JsonStateWriter writer = new JsonStateWriter(file(), write);
        writer.schedule(value("A"));
        CompletableFuture<Void> failing = flushing(writer);
        write.awaitPaused();
        writer.schedule(value("B"));
        write.release();
        assertThrows(Exception.class, () -> failing.get(5, TimeUnit.SECONDS));

        writer.flush();
        assertEquals("B", read());
        assertEquals(List.of("B"), write.written, "the failed snapshot is not written after the newer one");
    }

    @Test
    void aCloseBesideAFlushLeavesTheNewestSnapshotAndRefusesLaterOnes() throws Exception {
        PausedWrite write = new PausedWrite();
        JsonStateWriter writer = new JsonStateWriter(file(), write);
        writer.schedule(value("A"));
        CompletableFuture<Void> flushing = flushing(writer);
        write.awaitPaused();
        writer.schedule(value("B"));
        CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
            try {
                writer.close();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });

        write.release();
        flushing.get(5, TimeUnit.SECONDS);
        closing.get(5, TimeUnit.SECONDS);
        assertEquals("B", read());
        assertThrows(IllegalStateException.class, () -> writer.schedule(value("C")));
    }

    @Test
    void aCloseThatFailsBesideOneThatSucceedsLeavesTheWriterClosed() throws Exception {
        PausedWrite write = new PausedWrite();
        write.failFirst.set(true);
        JsonStateWriter writer = new JsonStateWriter(file(), write);
        writer.schedule(value("A"));
        CompletableFuture<Void> failing = closing(writer);
        write.awaitPaused();
        CompletableFuture<Void> second = closing(writer);
        // The second close is waiting for the first's write.
        Thread.sleep(200);

        write.release();
        assertThrows(Exception.class, () -> failing.get(5, TimeUnit.SECONDS));
        second.get(5, TimeUnit.SECONDS);
        assertEquals("A", read());
        assertThrows(IllegalStateException.class, () -> writer.schedule(value("B")), "a close succeeded, so the writer stays closed");
    }

    @Test
    void aFailedCloseLeavesTheWriterOpenToBeClosedAgain() throws Exception {
        PausedWrite write = new PausedWrite();
        write.failFirst.set(true);
        write.release();
        JsonStateWriter writer = new JsonStateWriter(file(), write);
        writer.schedule(value("A"));
        assertThrows(IOException.class, writer::close);

        writer.schedule(value("B"));
        writer.close();
        assertEquals("B", read());
        assertThrows(IllegalStateException.class, () -> writer.schedule(value("C")));
    }

    private Path file() {
        return this.directory.resolve("state.json");
    }

    private String read() throws IOException {
        return JsonFiles.read(file()).get("value").getAsString();
    }

    private static JsonObject value(String value) {
        JsonObject json = new JsonObject();
        json.addProperty("value", value);
        return json;
    }

    private static CompletableFuture<Void> closing(JsonStateWriter writer) {
        return CompletableFuture.runAsync(() -> {
            try {
                writer.close();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static CompletableFuture<Void> flushing(JsonStateWriter writer) {
        return CompletableFuture.runAsync(() -> {
            try {
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    /** Writes the file, pausing the first write until released; it may fail the first write when it resumes. */
    private static final class PausedWrite implements JsonStateWriter.Write {
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        final AtomicBoolean failFirst = new AtomicBoolean();
        final List<String> written = new CopyOnWriteArrayList<>();

        @Override
        public void write(Path file, JsonElement snapshot) throws IOException {
            if (this.first.getAndSet(false)) {
                this.paused.countDown();
                try {
                    assertTrue(this.released.await(10, TimeUnit.SECONDS), "the paused write was released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                if (this.failFirst.get()) throw new IOException("The disk refused the write");
            }
            JsonElement value = snapshot.isJsonObject() ? snapshot.getAsJsonObject().get("value") : null;
            if (value != null) this.written.add(value.getAsString());
            JsonStateWriter.Write.FILE.write(file, snapshot);
        }

        void awaitPaused() throws InterruptedException {
            assertTrue(this.paused.await(5, TimeUnit.SECONDS), "a write started");
        }

        void release() {
            this.released.countDown();
        }
    }
}
