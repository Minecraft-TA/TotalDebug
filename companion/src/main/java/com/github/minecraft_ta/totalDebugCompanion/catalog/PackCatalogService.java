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

    public record Capturing() implements State {
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

    public Optional<CatalogIndex> index() {
        return state() instanceof Ready ready ? Optional.of(ready.index()) : Optional.empty();
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

    public void capturing() {
        set(new Capturing());
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
            if (!(this.state instanceof Ready ready) || ready.index().catalog().inventoryId().equals(inventoryId)) {
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

    private void apply(long expectedGeneration, State state) {
        synchronized (this) {
            if (this.generation != expectedGeneration) {
                return;
            }
            this.state = state;
        }
        SwingUtilities.invokeLater(() -> this.listeners.forEach(Runnable::run));
    }
}
