package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * The pack catalog of one project. Minecraft captures and writes it next to the runtime inventory; Companion reads the
 * saved catalog when a project opens and the announced one while the game is connected. A catalog that belongs to
 * another runtime inventory is not shown.
 */
public final class PackCatalogService {
    public sealed interface State permits None, Capturing, Ready, Stale, Failed {
    }

    /** No catalog has been captured for this project. */
    public record None() implements State {
    }

    /**
     * Minecraft captures the catalog again, such as after a resource reload; {@code previous} is the catalog shown until
     * then, or null.
     */
    public record Capturing(CatalogIndex previous) implements State {
    }

    public record Ready(CatalogIndex index) implements State {
    }

    public record Stale(String detail) implements State {
    }

    public record Failed(String detail) implements State {
    }

    private final InstancePaths paths;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private State state = new None();
    private long generation;

    public PackCatalogService(InstancePaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public synchronized State state() {
        return this.state;
    }

    /** The catalog to show: the one ready, or while Minecraft captures it again, the one before. */
    public Optional<CatalogIndex> index() {
        return switch (state()) {
            case Ready ready -> Optional.of(ready.index());
            case Capturing capturing -> Optional.ofNullable(capturing.previous());
            default -> Optional.empty();
        };
    }

    /** Listeners run on the Swing thread after the state changed. */
    public Runnable addListener(Runnable listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> this.listeners.remove(listener);
    }

    /**
     * Loads the saved catalog when it belongs to the saved runtime inventory. Blocking; not on the Swing thread. A
     * state the game reported in the meantime wins.
     */
    public void restore() {
        long started;
        synchronized (this) {
            started = this.generation;
        }
        Path file = this.paths.catalog();
        if (!Files.isRegularFile(file)) {
            return;
        }
        State loaded;
        try {
            PackCatalog catalog = PackCatalog.read(file);
            Path inventory = this.paths.inventory();
            RuntimeInventory runtime = Files.isRegularFile(inventory) ? RuntimeInventory.read(inventory) : null;
            loaded = runtime != null && catalog.inventoryId().equals(runtime.id())
                    ? new Ready(index(catalog, runtime))
                    : new Stale("The captured content belongs to a different runtime; connect Minecraft to capture it again");
        } catch (IOException | RuntimeException failure) {
            loaded = new Failed(failure.getMessage());
        }
        apply(started, loaded);
    }

    /**
     * Minecraft captures the catalog; the one shown stays until the new one is ready, so the pages do not empty in
     * between, and nothing they show changes until then. Taken as one step with the state it replaces, so the newest
     * catalog ready stays shown.
     */
    public void capturing() {
        boolean shownBefore;
        synchronized (this) {
            this.generation++;
            CatalogIndex shown = shown(this.state);
            shownBefore = shown != null;
            this.state = new Capturing(shown);
        }
        // Pages that show the catalog go on showing it; only one without a catalog says it is being captured.
        if (!shownBefore) SwingUtilities.invokeLater(() -> this.listeners.forEach(Runnable::run));
    }

    /**
     * Takes a catalog the game announced for {@code inventoryId}. Its place among other announcements is fixed now;
     * the file is read on {@code loader}, and a state reported meanwhile wins over it.
     */
    public void accept(String inventoryId, Path file, Executor loader) {
        long started;
        synchronized (this) {
            started = ++this.generation;
        }
        loader.execute(() -> apply(started, load(inventoryId, file)));
    }

    private State load(String inventoryId, Path file) {
        State loaded;
        try {
            if (!file.toAbsolutePath().normalize().equals(this.paths.catalog().toAbsolutePath().normalize())) {
                throw new IOException("Minecraft announced a pack catalog outside this project: " + file);
            }
            PackCatalog catalog = PackCatalog.read(file);
            if (!catalog.inventoryId().equals(inventoryId)) {
                throw new IOException("The pack catalog belongs to runtime " + catalog.inventoryId()
                        + ", not the announced " + inventoryId);
            }
            RuntimeInventory runtime = Files.isRegularFile(this.paths.inventory()) ? RuntimeInventory.read(this.paths.inventory()) : null;
            loaded = new Ready(index(catalog, runtime));
        } catch (IOException | RuntimeException failure) {
            loaded = new Failed(failure.getMessage());
        }
        return loaded;
    }

    public void failed(String detail) {
        set(new Failed(detail.isBlank() ? "Minecraft could not capture the pack catalog" : detail));
    }

    private static CatalogIndex index(PackCatalog catalog, RuntimeInventory runtime) {
        List<Path> vanilla = runtime != null && runtime.id().equals(catalog.inventoryId())
                && catalog.mods().stream().anyMatch(mod -> mod.id().equals("minecraft"))
                ? ModResources.vanillaArchives(runtime.sources()) : List.of();
        return new CatalogIndex(catalog, vanilla);
    }

    /** A different runtime inventory makes the shown catalog outdated until its own catalog arrives. */
    public void inventoryAnnounced(String inventoryId) {
        synchronized (this) {
            CatalogIndex shown = shown(this.state);
            if (shown == null || shown.catalog().inventoryId().equals(inventoryId)) {
                return;
            }
        }
        set(new Stale("The runtime changed; waiting for Minecraft to capture its content"));
    }

    private void set(State state) {
        long current;
        synchronized (this) {
            current = ++this.generation;
        }
        apply(current, state);
    }

    /**
     * Takes {@code state} and tells the listeners. A catalog ready again is told even when its content is the same: the
     * reload that captured it may have changed the files of a pack the pages list.
     */
    private void apply(long expectedGeneration, State state) {
        synchronized (this) {
            if (this.generation != expectedGeneration) {
                return;
            }
            this.state = state;
        }
        SwingUtilities.invokeLater(() -> this.listeners.forEach(Runnable::run));
    }

    private static CatalogIndex shown(State state) {
        return switch (state) {
            case Ready ready -> ready.index();
            case Capturing capturing -> capturing.previous();
            default -> null;
        };
    }
}
