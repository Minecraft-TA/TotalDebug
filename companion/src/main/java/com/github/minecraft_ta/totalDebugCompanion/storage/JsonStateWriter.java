package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totalDebugCompanion.util.Strand;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;

/**
 * Coalesces snapshots and writes the newest, without letting an older write land after a newer one. Scheduling never
 * waits for the disk: the writer's state lock is held only to read or replace the pending snapshot, and the writes are
 * ordered by a lock of their own (docs/SYSTEMS.md, section 1).
 */
public final class JsonStateWriter implements AutoCloseable {
    /** Writes a snapshot to the file; replaceable so a test can pause or fail a write. */
    @FunctionalInterface
    public interface Write {
        /** Writes the file as JSON. */
        Write FILE = JsonFiles::write;

        void write(Path file, JsonElement snapshot) throws IOException;
    }

    private final Path file;
    private final Write write;
    /** Held through a write, so writes run one at a time, each taking the newest snapshot when it starts. */
    private final Object writing = new Object();
    /**
     * Where the scheduled saves run, one at a time: saves that come due while a slow write runs wait here, not on the
     * shared file workers, which they would otherwise each hold waiting for {@link #writing}.
     */
    private final Strand saves = Workers.fileStrand();
    private ScheduledFuture<?> scheduled;
    private JsonElement pending;
    private boolean closed;

    public JsonStateWriter(Path file) {
        this(file, Write.FILE);
    }

    public JsonStateWriter(Path file, Write write) {
        this.file = file;
        this.write = write;
    }

    public void schedule(JsonElement snapshot) {
        JsonElement copy = snapshot.deepCopy();
        synchronized (this) {
            if (this.closed) {
                throw new IllegalStateException("State writer is closed: " + this.file);
            }
            this.pending = copy;
            if (this.scheduled != null) {
                this.scheduled.cancel(false);
            }
            this.scheduled = Workers.later(500, this.saves, () -> {
                try {
                    drain();
                } catch (IOException exception) {
                    System.err.println("Unable to save " + this.file + ": " + exception.getMessage());
                }
            });
        }
    }

    /** Returns once every snapshot scheduled before the call is on disk; a failed write stays pending. */
    public void flush() throws IOException {
        drain();
    }

    /**
     * Writes the pending snapshot and refuses later ones. A failed close leaves the writer open with its snapshot pending,
     * so it can be closed again. Closes run one at a time, so a failed one cannot open a writer another closed since.
     */
    @Override
    public void close() throws IOException {
        synchronized (this.writing) {
            synchronized (this) {
                this.closed = true;
            }
            try {
                drain();
            } catch (IOException | RuntimeException failure) {
                synchronized (this) {
                    this.closed = false;
                }
                throw failure;
            }
        }
    }

    /** Writes the newest pending snapshot, after any write under way. */
    private void drain() throws IOException {
        synchronized (this.writing) {
            JsonElement snapshot;
            synchronized (this) {
                if (this.scheduled != null) {
                    this.scheduled.cancel(false);
                    this.scheduled = null;
                }
                snapshot = this.pending;
            }
            if (snapshot == null) return;
            this.write.write(this.file, snapshot);
            synchronized (this) {
                // A snapshot scheduled while this one was written stays pending for its own timer.
                if (this.pending == snapshot) this.pending = null;
            }
        }
    }
}
