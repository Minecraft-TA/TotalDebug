package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelId;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderBackend;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderRequest;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderResourceRoot;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws item icons from the resource snapshot the game last published. The renderer and its archive are confined to
 * one worker thread; results are cached per snapshot. Icons are unavailable until a snapshot arrives and for models
 * the offline renderer does not support.
 */
public final class ItemIconService implements AutoCloseable {
    private static final int MAX_CACHED = 512;

    private record Snapshot(Path archive, int layers) {
    }

    private record Key(String model, Map<Integer, Integer> tints, int size) {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion item icons")
            .unstarted(task));
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<Key, Optional<BufferedImage>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Optional<BufferedImage>> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private volatile Snapshot snapshot;
    private Snapshot opened;
    private ItemRenderBackend backend;
    private volatile boolean closed;

    /** Adopts a newer resource snapshot; views are told on the EDT so they can draw again. */
    public void accept(String archive, int layers) {
        Snapshot next = new Snapshot(Path.of(archive), layers);
        if (this.closed || next.equals(this.snapshot)) {
            return;
        }
        this.snapshot = next;
        SwingUtilities.invokeLater(() -> this.listeners.forEach(Runnable::run));
    }

    public boolean hasSnapshot() {
        return this.snapshot != null;
    }

    /** Registers a listener for new snapshots and returns its removal. */
    public Runnable addListener(Runnable listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> this.listeners.remove(listener);
    }

    /** Renders {@code model} (for example {@code minecraft:item/furnace}); empty when it cannot be drawn. */
    public CompletableFuture<Optional<BufferedImage>> render(String model, Map<Integer, Integer> tints, int size) {
        if (this.closed || model == null || model.isBlank() || this.snapshot == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Key key = new Key(model, Map.copyOf(tints), size);
        return CompletableFuture.supplyAsync(() -> renderOnWorker(key), this.worker);
    }

    /** The conventional inventory model of an item registry id: {@code ns:path} becomes {@code ns:item/path}. */
    public static String itemModel(String itemId) {
        int separator = itemId.indexOf(':');
        return separator < 0 ? "" : itemId.substring(0, separator) + ":item/" + itemId.substring(separator + 1);
    }

    private Optional<BufferedImage> renderOnWorker(Key key) {
        Snapshot current = this.snapshot;
        if (this.closed || current == null) {
            return Optional.empty();
        }
        if (!current.equals(this.opened)) {
            closeBackend();
            this.cache.clear();
            List<ItemRenderResourceRoot> roots = new ArrayList<>(current.layers());
            for (int layer = 0; layer < current.layers(); layer++) {
                roots.add(ItemRenderResourceRoot.nested(current.archive(), "layers/" + layer));
            }
            try {
                this.backend = ItemRenderBackend.openResourceRoots(roots);
                this.opened = current;
            } catch (IOException exception) {
                System.getLogger(ItemIconService.class.getName()).log(System.Logger.Level.WARNING,
                        "Unable to open item icon resources " + current.archive(), exception);
                return Optional.empty();
            }
        }
        return this.cache.computeIfAbsent(key, ignored -> {
            try {
                return Optional.of(this.backend.render(
                        new ItemRenderRequest(ItemModelId.parse(key.model()), key.size(), key.tints())));
            } catch (IOException | RuntimeException unsupported) {
                return Optional.empty();
            }
        });
    }

    private void closeBackend() {
        if (this.backend == null) {
            return;
        }
        try {
            this.backend.close();
        } catch (IOException exception) {
            System.getLogger(ItemIconService.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to close item icon resources", exception);
        }
        this.backend = null;
        this.opened = null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.listeners.clear();
        this.worker.execute(this::closeBackend);
        this.worker.shutdown();
    }
}
