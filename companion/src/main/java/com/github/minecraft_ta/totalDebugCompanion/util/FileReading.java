package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * An owner's value read from files others write, such as the keys {@code options.txt} assigns (docs/SYSTEMS.md, section
 * 2). Nothing watches the files. The whole contract:
 * <ul>
 *     <li>Reads run one at a time, in order, on the reading's strand.</li>
 *     <li>{@link #value()} returns the value published last, or reads it now where none was.</li>
 *     <li>{@link #refresh()} reads once after it was asked, as after Companion wrote the file or the game told of a change;
 *     requests made while one waits are that one, and a read under way when one is made publishes nothing, since the
 *     one it waits for is newer. The first value it reads is told, for followers that show it without
 *     asking, such as a tab's title.</li>
 *     <li>The user coming back to Companion from another program ({@link WindowFocus#returned()}) refreshes a value read
 *     before; a value nobody asked for stays unread.</li>
 *     <li>A read that fails keeps the value read before, and the next read that succeeds is told.</li>
 *     <li>{@link #changed()} fires only when a read publishes another value than the one before.</li>
 *     <li>Once closed, no read starts and none publishes.</li>
 * </ul>
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
    private volatile boolean closed;
    /** Whether a refresh waits on the strand, which later requests join, and whether one of them reads a first value. */
    private boolean queued;
    private boolean first;

    public FileReading(Reader<T> reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.stopFollowingFocus = WindowFocus.returned().subscribe(() -> request(false));
    }

    /** Fires on this reading's strand after a read published another value than the one before. */
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

    /** Reads once after this request, and tells the first value it reads. */
    public void refresh() {
        request(true);
    }

    /** Asks for one read on the strand, which reads a value nobody asked for only where {@code firstToo}. */
    private void request(boolean firstToo) {
        synchronized (this) {
            this.first |= firstToo;
            if (this.queued) return;
            this.queued = true;
        }
        this.strand.execute(() -> {
            boolean readFirst;
            synchronized (this) {
                this.queued = false;
                readFirst = this.first;
                this.first = false;
            }
            if (this.last == null && !this.failed && !readFirst) return;
            try {
                read(true);
            } catch (IOException | RuntimeException unreadable) {
                // Told by the next read that succeeds.
            }
        });
    }

    /**
     * Reads the value, publishes it and tells the followers where it changed, or with {@code tellFirst} where it is the
     * first. On the strand only.
     */
    private T read(boolean tellFirst) throws IOException {
        if (this.closed) throw new IOException("The project closed");
        T now;
        try {
            now = this.reader.read();
        } catch (IOException | RuntimeException failure) {
            this.failed = true;
            throw failure instanceof IOException unreadable ? unreadable : new IOException(failure);
        }
        if (this.closed) return now;
        synchronized (this) {
            // A request made while this read ran reads again and publishes: the newest wins, as in the page loader.
            if (this.queued) return now;
        }
        Read<T> before = this.last;
        boolean tell = this.failed || (before == null ? tellFirst : !Objects.equals(before.value(), now));
        this.last = new Read<>(now);
        this.failed = false;
        if (tell) this.changed.fire();
        return now;
    }

    /** No read starts or publishes from now on; the value stays published. */
    @Override
    public void close() {
        this.closed = true;
        this.stopFollowingFocus.run();
    }
}
