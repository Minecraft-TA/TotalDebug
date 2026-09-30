package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.resource.ResourceLoader;
import com.github.minecraft_ta.totalDebugCompanion.util.FileWatch;
import com.github.minecraft_ta.totalDebugCompanion.util.Strand;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiConsumer;

/**
 * Opens resources of folder packs in another program, such as an image editor, and takes each save that program makes as
 * a change Companion made: recorded, revertible from Changes, and used by the game at once, as a save in Companion is
 * (see {@link ResourceEdits#adopt}). A file stays followed until the project closes, whether its tab is open or not.
 */
public final class ExternalEdits implements AutoCloseable {
    /** How long a file stays unchanged before it is taken: programs write in several steps, such as a copy then a rename. */
    private static final long SETTLE_MILLIS = 300;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** The empty {@code IEND} chunk every PNG ends with: its length, type and checksum. */
    private static final byte[] PNG_END = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    private final ResourceEdits edits;
    /** The files followed, by absolute file. */
    private final Map<Path, Followed> followed = new ConcurrentHashMap<>();
    /** Where a save is taken, one at a time, as it is adopted in order. */
    private final Strand strand = Workers.strand();
    private boolean closed;

    /** A followed file, what it held when a program first opened it, and who hears about its saves. */
    private static final class Followed {
        final String path;
        final Path pack;
        final byte[] before;
        final List<BiConsumer<ResourceEdits.Saved, Throwable>> listeners = new CopyOnWriteArrayList<>();
        Runnable unwatch = () -> { };
        ScheduledFuture<?> pending;
        /** Counts the writes seen, so a take handed on before a later write takes nothing. Under the edits' lock. */
        long writes;

        Followed(String path, Path pack, byte[] before) {
            this.path = path;
            this.pack = pack;
            this.before = before;
        }
    }

    ExternalEdits(ResourceEdits edits) {
        this.edits = Objects.requireNonNull(edits, "edits");
    }

    /**
     * Opens {@code path} of the folder pack {@code pack} in {@code program}, a program file, or in the system's app for it
     * when null or blank, and follows the file from then on. Blocking, as starting a program can be.
     */
    public void open(String path, Path pack, String program) throws IOException {
        boolean followedBefore = follows(path, pack);
        Path file = follow(path, pack);
        try {
            launch(program, file);
        } catch (IOException | RuntimeException failed) {
            // Nothing was opened, so nothing else's saves are taken: only a file followed before stays followed.
            if (!followedBefore) stopFollowing(file);
            throw failed;
        }
    }

    /**
     * Follows {@code path} of {@code pack}, which must exist, and returns its file; each save another program makes to it
     * is taken. Blocking.
     */
    Path follow(String path, Path pack) throws IOException {
        Path file = pack.resolve(path).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IOException(file + " does not exist");
        synchronized (this) {
            if (this.closed) throw new IOException("The project is closing");
            if (!this.followed.containsKey(file)) {
                Followed followed = new Followed(path, pack, Files.readAllBytes(file));
                Path name = file.getFileName();
                followed.unwatch = FileWatch.shared().watch(file.getParent(), name::equals, () -> settle(followed));
                this.followed.put(file, followed);
            }
        }
        return file;
    }

    /**
     * Runs {@code listener} with the result of each save a program makes to {@code path} of {@code pack} once the game
     * used it, or with why that failed; returns what removes it.
     */
    public Runnable addListener(String path, Path pack, BiConsumer<ResourceEdits.Saved, Throwable> listener) {
        Followed file = this.followed.get(pack.resolve(path).toAbsolutePath().normalize());
        if (file == null) return () -> { };
        file.listeners.add(listener);
        return () -> file.listeners.remove(listener);
    }

    /** Whether a program's saves to {@code path} of {@code pack} are taken. */
    public boolean follows(String path, Path pack) {
        return this.followed.containsKey(pack.resolve(path).toAbsolutePath().normalize());
    }

    private synchronized void stopFollowing(Path file) {
        Followed followed = this.followed.remove(file);
        if (followed == null) return;
        followed.unwatch.run();
        if (followed.pending != null) followed.pending.cancel(false);
    }

    /** Takes the file once it stayed unchanged for a moment. */
    private synchronized void settle(Followed file) {
        if (this.closed) return;
        if (file.pending != null) file.pending.cancel(false);
        long write = ++file.writes;
        file.pending = Workers.later(SETTLE_MILLIS, this.strand, () -> take(file, write));
    }

    private void take(Followed file, long write) {
        synchronized (this) {
            // Another write came since, whose own settle takes the file.
            if (this.closed || write != file.writes) return;
        }
        Path location = file.pack.resolve(file.path);
        byte[] seen;
        try {
            // Gone for now, such as between a program's delete and rename; its next write comes as another event.
            if (!Files.isRegularFile(location)) return;
            seen = Files.readAllBytes(location);
            if (!readable(file.path, seen)) return;
        } catch (IOException unreadable) {
            // Still being written; the program's next write comes as another event.
            return;
        }
        this.edits.adopt(file.path, file.pack, file.before, seen).whenComplete((saved, failure) -> {
            if (saved == null && failure == null) return;
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            file.listeners.forEach(listener -> listener.accept(saved, cause));
        });
    }

    /**
     * Whether {@code content} is whole: a texture starts with the PNG signature and ends with its closing chunk, and is no
     * larger than a texture Companion reads; anything else is taken as it is. Nothing is decoded, however large the image.
     */
    static boolean readable(String path, byte[] content) {
        if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) return true;
        if (content.length > ResourceLoader.MAXIMUM_PNG_BYTES || content.length < PNG_SIGNATURE.length + PNG_END.length) return false;
        return Arrays.equals(content, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)
                && Arrays.equals(content, content.length - PNG_END.length, content.length, PNG_END, 0, PNG_END.length);
    }

    /** Opens {@code file} in {@code program}, or in the system's app for it when null or blank. */
    static void launch(String program, Path file) throws IOException {
        if (program == null || program.isBlank()) {
            if (!Desktop.isDesktopSupported()) throw new IOException("This system opens no files in other apps; choose an image editor in Settings");
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.EDIT)) {
                try {
                    desktop.edit(file.toFile());
                    return;
                } catch (IOException | UnsupportedOperationException noEditor) {
                    // Without an app registered to edit the file type, the one that opens it.
                }
            }
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new IOException("This system opens no files in other apps; choose an image editor in Settings");
            }
            try {
                desktop.open(file.toFile());
            } catch (IOException noApp) {
                throw new IOException("No app is set to open " + file.getFileName() + "; choose an image editor in Settings", noApp);
            }
            return;
        }
        Path executable = Path.of(program);
        if (!Files.exists(executable)) throw new IOException(program + " does not exist; choose another image editor in Settings");
        // A macOS app is a folder, which the system starts.
        List<String> command = Files.isDirectory(executable) && program.endsWith(".app")
                ? List.of("open", "-a", program, file.toString()) : List.of(program, file.toString());
        new ProcessBuilder(command).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start();
    }

    /** Opens {@code path} of {@code pack} off the calling thread, as {@link #open} does. */
    public CompletableFuture<Void> openLater(String path, Path pack, String program) {
        return CompletableFuture.runAsync(() -> {
            try {
                open(path, pack, program);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, Workers.files());
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        List.copyOf(this.followed.keySet()).forEach(this::stopFollowing);
    }
}
