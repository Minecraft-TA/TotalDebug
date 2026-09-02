package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Coalesces snapshots without letting an older background save overwrite a flush. */
public final class JsonStateWriter implements AutoCloseable {
    private final Path file;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("Companion state writer").unstarted(task));
    private ScheduledFuture<?> scheduled;
    private JsonElement pending;
    private boolean closed;

    public JsonStateWriter(Path file) {
        this.file = file;
    }

    public synchronized void schedule(JsonElement snapshot) {
        if (this.closed) {
            throw new IllegalStateException("State writer is closed: " + this.file);
        }
        this.pending = snapshot.deepCopy();
        if (this.scheduled != null) {
            this.scheduled.cancel(false);
        }
        this.scheduled = this.executor.schedule(() -> {
            try {
                flush();
            } catch (IOException exception) {
                System.err.println("Unable to save " + this.file + ": " + exception.getMessage());
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    public synchronized void flush() throws IOException {
        if (this.scheduled != null) {
            this.scheduled.cancel(false);
            this.scheduled = null;
        }
        if (this.pending != null) {
            JsonFiles.write(this.file, this.pending);
            this.pending = null;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        flush();
        this.closed = true;
        this.executor.shutdown();
    }
}
