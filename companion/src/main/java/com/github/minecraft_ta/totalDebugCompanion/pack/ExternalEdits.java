package com.github.minecraft_ta.totalDebugCompanion.pack;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Opens resources of folder packs in another program, such as an image editor, and takes each save that program makes as
 * a change Companion made: recorded, revertible from Changes, and used by the game at once, as a save in Companion is
 * (see {@link ResourceEdits#adopt}). A file stays followed until the project closes, whether its tab is open or not.
 */
public final class ExternalEdits implements AutoCloseable {
    /** How long a file stays unchanged before it is taken: programs write in several steps, such as a copy then a rename. */
    private static final long SETTLE_MILLIS = 300;

    private final ResourceEdits edits;
    /** The files followed, by absolute file. */
    private final Map<Path, Followed> followed = new ConcurrentHashMap<>();
    /** The folders watched, by folder. */
    private final Map<Path, WatchKey> folders = new ConcurrentHashMap<>();
    private WatchService watcher;
    private ScheduledExecutorService settle;
    private boolean closed;

    /** A followed file, what it held when a program first opened it, and who hears about its saves. */
    private static final class Followed {
        final String path;
        final Path pack;
        final byte[] before;
        final List<BiConsumer<ResourceEdits.Saved, Throwable>> listeners = new CopyOnWriteArrayList<>();
        ScheduledFuture<?> pending;

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
        Path file = follow(path, pack);
        launch(program, file);
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
            if (this.followed.containsKey(file)) return file;
            if (this.watcher == null) start();
            Path folder = file.getParent();
            if (!this.folders.containsKey(folder)) {
                this.folders.put(folder, folder.register(this.watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY));
            }
            this.followed.put(file, new Followed(path, pack, Files.readAllBytes(file)));
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

    private void start() throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.settle = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TotalDebug external edits");
            thread.setDaemon(true);
            return thread;
        });
        WatchService watching = this.watcher;
        Thread thread = new Thread(() -> watch(watching), "TotalDebug external edit watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch(WatchService watching) {
        while (true) {
            WatchKey key;
            try {
                key = watching.take();
            } catch (InterruptedException | ClosedWatchServiceException stopped) {
                return;
            }
            Path folder = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    // Which files changed is lost: every followed file of the folder is looked at.
                    this.followed.forEach((file, followedFile) -> {
                        if (file.getParent().equals(folder)) settle(followedFile);
                    });
                } else if (event.context() instanceof Path name) {
                    Followed file = this.followed.get(folder.resolve(name));
                    if (file != null) settle(file);
                }
            }
            if (!key.reset()) this.folders.remove(folder);
        }
    }

    /** Takes the file once it stayed unchanged for a moment. */
    private synchronized void settle(Followed file) {
        if (this.closed) return;
        if (file.pending != null) file.pending.cancel(false);
        file.pending = this.settle.schedule(() -> take(file), SETTLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void take(Followed file) {
        Path location = file.pack.resolve(file.path);
        try {
            // Gone for now, such as between a program's delete and rename; its next write comes as another event.
            if (!Files.isRegularFile(location)) return;
            if (!readable(file.path, Files.readAllBytes(location))) return;
        } catch (IOException unreadable) {
            // Still being written; the program's next write comes as another event.
            return;
        }
        this.edits.adopt(file.path, file.pack, file.before).whenComplete((saved, failure) -> {
            if (saved == null && failure == null) return;
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            file.listeners.forEach(listener -> listener.accept(saved, cause));
        });
    }

    /** Whether {@code content} is whole: a texture decodes; anything else is taken as it is. */
    private static boolean readable(String path, byte[] content) {
        if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) return true;
        try {
            return ImageIO.read(new ByteArrayInputStream(content)) != null;
        } catch (IOException | RuntimeException partial) {
            return false;
        }
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
        });
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.followed.clear();
        this.folders.clear();
        if (this.settle != null) this.settle.shutdownNow();
        if (this.watcher != null) {
            try {
                this.watcher.close();
            } catch (IOException ignored) {
                // Closing only stops the watch.
            }
        }
    }
}
