package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * An owner's value read from files others write, such as the keys {@code options.txt} assigns (docs/SYSTEMS.md, section
 * 2). Nothing watches the files: the value is read when first asked for or when the owner asks ({@link #refresh()}), as
 * after Companion wrote the file or the game told of a change; a value read before is read again when the user comes
 * back to Companion from another program ({@link WindowFocus#returned()}). Reads run one at a time, in order, on the reading's strand, and
 * {@link #changed()} fires only when a read found another value than the one before, or published the first value
 * without being asked for it.
 */
public final class FileReading<T> implements AutoCloseable {
    /** Reads the value; a missing file is a value too, such as nothing assigned. Blocking. */
    @FunctionalInterface
    public interface Reader<T> {
        T read() throws IOException;
    }

    /** A value as read, so a value that is null can be told from none read yet. */
    private record Read<T>(T value) {
    }

    private final Reader<T> reader;
    private final Signal changed = new Signal();
    private final Strand strand = Workers.strand();
    private final Runnable stopFollowingFocus;
    /** The value read last, or null before the first read: published, so it is read without the strand. */
    private volatile Read<T> last;
    /** Whether the last read failed, so the next one that succeeds is told: a page may show the failure. Strand only. */
    private boolean failed;
    /** Set once the reading closes, so no read publishes from then on, also one under way. */
    private volatile boolean closed;

    public FileReading(Reader<T> reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        // A value nobody asked for yet stays unread.
        this.stopFollowingFocus = WindowFocus.returned().subscribe(() -> this.strand.execute(() -> {
            if (this.last != null || this.failed) readAgain();
        }));
    }

    /** Fires on this reading's strand after a read found another value than the one before. */
    public Signal changed() {
        return this.changed;
    }

    /** The value read last, as published, without reading; empty where none was read yet. */
    public Optional<T> published() {
        Read<T> held = this.last;
        return held == null ? Optional.empty() : Optional.ofNullable(held.value());
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
                read.complete(now != null ? now.value() : read(false));
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

    /**
     * Reads again. Where nothing was read yet, the first value is told too, for followers that show it without asking,
     * such as a tab's title.
     */
    public void refresh() {
        this.strand.execute(this::readAgain);
    }

    /** Reads on the strand where a failure only keeps the value read before; the next read that succeeds is told. */
    private void readAgain() {
        try {
            read(true);
        } catch (IOException | RuntimeException unreadable) {
            // Told by the next read that succeeds.
        }
    }

    /**
     * Reads the value, publishes it and tells the followers where it changed, or with {@code tellFirst} where it is the
     * first. On the strand only.
     */
    private T read(boolean tellFirst) throws IOException {
        T now;
        try {
            now = this.reader.read();
        } catch (IOException | RuntimeException failure) {
            this.failed = true;
            throw failure instanceof IOException unreadable ? unreadable : new IOException(failure);
        }
        if (this.closed) return now;
        Read<T> before = this.last;
        boolean tell = this.failed || (before == null ? tellFirst : !Objects.equals(before.value(), now));
        this.last = new Read<>(now);
        this.failed = false;
        if (tell) this.changed.fire();
        return now;
    }

    /** Refreshes no more, whoever asks; the value stays published. */
    @Override
    public void close() {
        this.closed = true;
        this.stopFollowingFocus.run();
    }
}
