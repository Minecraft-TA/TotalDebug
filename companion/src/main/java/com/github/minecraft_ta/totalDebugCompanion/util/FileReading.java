package com.github.minecraft_ta.totalDebugCompanion.util;

import java.util.Optional;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The owner of a value read from a file others write, such as the keys {@code options.txt} assigns (docs/SYSTEMS.md,
 * section 2). Every read of the file and every change of the value runs on this reading's strand, in order; the value is
 * published as it was read, and {@link #changed()} fires only when a read found another value than the one before.
 *
 * <p>A write is read once it has settled, as a file can be written in parts. A read that fails keeps the value read
 * before, and is tried again after 1, 5 and 30 seconds and at the next write; the next read that succeeds is told, since a
 * page may show the failure. The file's folder is followed through {@link FileWatch}, also before it exists.</p>
 *
 * <p>A reading may also follow a whole folder, such as a world's, where some of its entries make up the value.</p>
 */
public final class FileReading<T> implements AutoCloseable {
    /** Reads the value a file or folder holds; a missing one is a value too, such as nothing assigned. Blocking. */
    @FunctionalInterface
    public interface Reader<T> {
        T read(Path file) throws IOException;
    }

    private static final List<Long> RETRY_MILLIS = List.of(1_000L, 5_000L, 30_000L);

    /** A value as read, so a value that is null can be told from none read yet. */
    private record Read<T>(T value) {
    }

    private final Reader<T> reader;
    /** For a reading of a folder, the names of its entries that make up the value; null for a reading of a file. */
    private final Predicate<Path> entries;
    private final long settleMillis;
    private final Signal changed = new Signal();
    private final Strand strand = Workers.strand();
    /** The value read last, or null before the first read: published, so it is read without the strand. */
    private volatile Read<T> last;
    // Changed on the strand only.
    private Path file;
    private Runnable unwatch = () -> { };
    /** Whether the file is watched; one that is not is read only when asked, as a world the game does not hold. */
    private boolean watching = true;
    private ScheduledFuture<?> pending;
    /**
     * Counts the reads asked for, counted where they are asked, also on the watcher's thread: a read waiting for a write
     * to settle, or one under way when another write is seen, gives way to the later one and publishes nothing.
     */
    private final AtomicLong generation = new AtomicLong();
    private boolean unreadable;
    /** Set as soon as the reading closes, so a read under way then publishes nothing. */
    private volatile boolean closed;

    /** Reads {@code file} with {@code reader}, and again after each write that stopped for {@code settle}. */
    public FileReading(Path file, Reader<T> reader, Duration settle) {
        this(file, null, reader, settle);
    }

    /**
     * Reads {@code folder} with {@code reader}, and again after each change of an entry that {@code entries} accepts that
     * stopped for {@code settle}.
     */
    public FileReading(Path folder, Predicate<Path> entries, Reader<T> reader, Duration settle) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.entries = entries;
        this.settleMillis = settle.toMillis();
        Path followed = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        long generation = this.generation.incrementAndGet();
        this.strand.execute(() -> follow(followed, generation));
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
                read.complete(now != null ? now.value() : readHere(0));
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
        long generation = this.generation.incrementAndGet();
        this.strand.execute(() -> read(0, generation));
    }

    /** Follows {@code file} from now on, as the current world after the game opened another; its value is read now. */
    public void moveTo(Path file) {
        Path followed = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        // A read of the file before gives way at once, so nothing it read is published after the move was asked for.
        long generation = this.generation.incrementAndGet();
        this.strand.execute(() -> follow(followed, generation));
    }

    /** Stops following the file; a read under way publishes nothing. */
    @Override
    public void close() {
        this.closed = true;
        this.strand.execute(() -> {
            this.unwatch.run();
            if (this.pending != null) this.pending.cancel(false);
        });
    }

    /**
     * Watches the file, or stops watching it while it is read only when asked ({@link #readNow()}), as a world the game
     * does not hold: on Windows, a watched folder cannot be deleted or renamed, as by the game's Delete World.
     */
    public void watch(boolean watch) {
        this.strand.execute(() -> {
            if (this.closed || this.watching == watch) return;
            this.watching = watch;
            if (watch) register();
            else unregister();
        });
    }

    private void follow(Path followed, long generation) {
        if (this.closed) return;
        unregister();
        this.file = followed;
        if (this.watching) register();
        read(0, generation);
    }

    private void register() {
        Path name = this.file.getFileName();
        this.unwatch = this.entries != null
                ? FileWatch.shared().watch(this.file, this.entries, this::written)
                : FileWatch.shared().watch(this.file.getParent(), name::equals, this::written);
    }

    private void unregister() {
        this.unwatch.run();
        this.unwatch = () -> { };
    }

    /** A write of the file was seen: it is read once no other came for the settle time. Any thread. */
    private void written() {
        // Counted here, so a read under way gives way at once.
        long generation = this.generation.incrementAndGet();
        this.strand.execute(() -> {
            if (this.closed) return;
            if (this.pending != null) this.pending.cancel(false);
            this.pending = Workers.later(this.settleMillis, this.strand, () -> read(0, generation));
        });
    }

    private void read(int attempt, long generation) {
        if (this.closed || generation != this.generation.get()) return;
        try {
            readHere(generation);
        } catch (IOException | RuntimeException unreadable) {
            if (attempt < RETRY_MILLIS.size()) {
                this.pending = Workers.later(RETRY_MILLIS.get(attempt), this.strand, () -> read(attempt + 1, generation));
            }
        }
    }

    /**
     * Reads the file, publishes what it holds and tells the followers where that changed. A read for {@code generation},
     * other than 0 for one a page waits for, publishes nothing where another write was seen meanwhile. On the strand only.
     */
    private T readHere(long generation) throws IOException {
        T now;
        try {
            now = this.reader.read(this.file);
        } catch (IOException | RuntimeException failure) {
            // A read another write overtook fails for nothing that is current: the next read is not told for it.
            if (generation == 0 || generation == this.generation.get()) this.unreadable = true;
            throw failure instanceof IOException unreadable ? unreadable : new IOException(failure);
        }
        if (generation != 0 && generation != this.generation.get() || this.closed) return now;
        Read<T> before = this.last;
        boolean tell = this.unreadable || before != null && !Objects.equals(before.value(), now);
        this.last = new Read<>(now);
        this.unreadable = false;
        if (tell) this.changed.fire();
        return now;
    }
}
