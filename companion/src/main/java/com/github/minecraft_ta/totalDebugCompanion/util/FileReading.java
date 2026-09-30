package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * The owner of a value read from a file others write, such as the keys {@code options.txt} assigns (docs/SYSTEMS.md,
 * section 2). Every read of the file and every change of the value runs on this reading's strand, in order; the value is
 * published as it was read, and {@link #changed()} fires only when a read found another value than the one before.
 *
 * <p>A write is read once it has settled, as a file can be written in parts. A read that fails keeps the value read
 * before, and is tried again after 1, 5 and 30 seconds and at the next write; the next read that succeeds is told, since a
 * page may show the failure. The file's folder is followed through {@link FileWatch}, also before it exists.</p>
 */
public final class FileReading<T> implements AutoCloseable {
    /** Reads the value a file holds; a missing file is a value too, such as nothing assigned. Blocking. */
    @FunctionalInterface
    public interface Reader<T> {
        T read(Path file) throws IOException;
    }

    private static final List<Long> RETRY_MILLIS = List.of(1_000L, 5_000L, 30_000L);

    /** A value as read, so a value that is null can be told from none read yet. */
    private record Read<T>(T value) {
    }

    private final Reader<T> reader;
    private final long settleMillis;
    private final Signal changed = new Signal();
    private final Strand strand = Workers.strand();
    /** The value read last, or null before the first read: published, so it is read without the strand. */
    private volatile Read<T> last;
    // Changed on the strand only.
    private Path file;
    private Runnable unwatch = () -> { };
    private ScheduledFuture<?> pending;
    /** Counts the reads asked for; one waiting for a write to settle gives way to a later one. */
    private long generation;
    private boolean unreadable;
    private boolean closed;

    /** Reads {@code file} with {@code reader}, and again after each write that stopped for {@code settle}. */
    public FileReading(Path file, Reader<T> reader, Duration settle) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.settleMillis = settle.toMillis();
        Path followed = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.strand.execute(() -> follow(followed));
    }

    /** Fires on this reading's strand after a read found another value than the one before. */
    public Signal changed() {
        return this.changed;
    }

    /**
     * The value read last; where none was read yet, it is read now, on this reading's strand, so it is the one later
     * changes are told against. Blocking then.
     */
    public T value() throws IOException {
        Read<T> held = this.last;
        if (held != null) return held.value();
        CompletableFuture<T> read = new CompletableFuture<>();
        this.strand.execute(() -> {
            try {
                Read<T> now = this.last;
                read.complete(now != null ? now.value() : readHere());
            } catch (IOException | RuntimeException failure) {
                read.completeExceptionally(failure);
            }
        });
        try {
            return read.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException unreadable) throw unreadable;
            throw new IOException(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Stopped while reading the file");
        }
    }

    /** Reads the file now rather than once a write settled, as after Companion wrote it. */
    public void readNow() {
        this.strand.execute(() -> read(0, ++this.generation));
    }

    /** Follows {@code file} from now on, as the current world after the game opened another; its value is read now. */
    public void moveTo(Path file) {
        Path followed = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.strand.execute(() -> follow(followed));
    }

    /** Stops following the file; a read under way is not told. */
    @Override
    public void close() {
        this.strand.execute(() -> {
            this.closed = true;
            this.unwatch.run();
            if (this.pending != null) this.pending.cancel(false);
        });
    }

    private void follow(Path followed) {
        if (this.closed) return;
        this.unwatch.run();
        this.file = followed;
        Path name = followed.getFileName();
        this.unwatch = FileWatch.shared().watch(followed.getParent(), name::equals, this::written);
        read(0, ++this.generation);
    }

    /** A write of the file was seen: it is read once no other came for the settle time. Any thread. */
    private void written() {
        this.strand.execute(() -> {
            if (this.closed) return;
            long generation = ++this.generation;
            if (this.pending != null) this.pending.cancel(false);
            this.pending = Workers.later(this.settleMillis, this.strand, () -> read(0, generation));
        });
    }

    private void read(int attempt, long generation) {
        if (this.closed || generation != this.generation) return;
        try {
            readHere();
        } catch (IOException | RuntimeException unreadable) {
            if (attempt < RETRY_MILLIS.size()) {
                this.pending = Workers.later(RETRY_MILLIS.get(attempt), this.strand, () -> read(attempt + 1, generation));
            }
        }
    }

    /** Reads the file, publishes what it holds and tells the followers where that changed. On the strand only. */
    private T readHere() throws IOException {
        T now;
        try {
            now = this.reader.read(this.file);
        } catch (IOException | RuntimeException failure) {
            this.unreadable = true;
            throw failure instanceof IOException unreadable ? unreadable : new IOException(failure);
        }
        Read<T> before = this.last;
        boolean tell = this.unreadable || before != null && !Objects.equals(before.value(), now);
        this.last = new Read<>(now);
        this.unreadable = false;
        if (tell && !this.closed) this.changed.fire();
        return now;
    }
}
