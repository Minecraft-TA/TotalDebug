package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelId;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderBackend;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderRequest;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemRenderResourceRoot;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ModelAppearance;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Draws item icons from the resource snapshot the game last published. The renderer and its archive are confined to
 * one worker thread; results are cached per snapshot. Icons are unavailable until a snapshot arrives and for models
 * the offline renderer does not support.
 */
public final class ItemIconService implements AutoCloseable {
    private static final int MAX_CACHED = 512;

    record Snapshot(Path archive, int layers) {
    }

    private static final Pattern SNAPSHOT_NAME = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip");
    private static final Pattern LAYER = Pattern.compile("layers/(\\d+)/");

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
    private final Map<String, Optional<BufferedImage>> fluids = new LinkedHashMap<>();
    private volatile Snapshot snapshot;
    private long adoptions;
    private volatile Function<String, Optional<CatalogIndex.ItemIcon>> itemLookup = itemId -> Optional.empty();
    private Snapshot opened;
    private Snapshot fluidsOpened;
    private FluidTextures fluidTextures;
    private ItemRenderBackend backend;
    private volatile boolean closed;

    /** Adopts a newer resource snapshot; views are told on the EDT so they can draw again. */
    public void accept(String archive, int layers) {
        adopt(start(), new Snapshot(Path.of(archive), layers));
    }

    /**
     * Adopts the newest snapshot the game saved in a project's preview directory, or none, so icons stay available
     * without a connection and never come from another project. The directory is read in the background; only the
     * newest restore adopts its result, and a snapshot announced meanwhile supersedes it.
     */
    public CompletableFuture<Void> restore(Path directory) {
        return restore(directory, ForkJoinPool.commonPool());
    }

    CompletableFuture<Void> restore(Path directory, Executor reader) {
        long generation = start();
        return CompletableFuture.runAsync(() -> adopt(generation, newestSnapshot(directory)), reader);
    }

    /** Starts an adoption, superseding every earlier one that has not finished. */
    private long start() {
        synchronized (this.listeners) {
            return ++this.adoptions;
        }
    }

    /** Replaces the snapshot, unless a newer adoption started since {@code generation}. */
    private void adopt(long generation, Snapshot next) {
        synchronized (this.listeners) {
            if (this.closed || generation != this.adoptions || Objects.equals(next, this.snapshot)) {
                return;
            }
            this.snapshot = next;
        }
        SwingUtilities.invokeLater(() -> this.listeners.forEach(Runnable::run));
    }

    static Snapshot newestSnapshot(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        List<Path> archives;
        try (Stream<Path> files = Files.list(directory)) {
            archives = files.filter(file -> SNAPSHOT_NAME.matcher(file.getFileName().toString()).matches())
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(ItemIconService::modified).reversed())
                    .toList();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        for (Path archive : archives) {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                int layers = zip.stream()
                        .map(ZipEntry::getName)
                        .map(LAYER::matcher)
                        .filter(Matcher::lookingAt)
                        .mapToInt(matcher -> Integer.parseInt(matcher.group(1)) + 1)
                        .max().orElse(0);
                if (layers > 0) {
                    return new Snapshot(archive.toAbsolutePath().normalize(), layers);
                }
            } catch (IOException | RuntimeException unreadable) {
                // A partly written or damaged archive is skipped in favor of an older complete one.
            }
        }
        return null;
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    public boolean hasSnapshot() {
        return this.snapshot != null;
    }

    Snapshot snapshot() {
        return this.snapshot;
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

    /** The tinted still texture of a fluid, as the game draws it; empty when it was not captured. */
    public CompletableFuture<Optional<BufferedImage>> fluidTexture(String fluidId) {
        if (this.closed || fluidId == null || fluidId.isBlank() || this.snapshot == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> fluidOnWorker(fluidId), this.worker);
    }

    private Optional<BufferedImage> fluidOnWorker(String fluidId) {
        Snapshot current = this.snapshot;
        if (this.closed || current == null) {
            return Optional.empty();
        }
        if (!current.equals(this.fluidsOpened)) {
            this.fluids.clear();
            this.fluidTextures = null;
            this.fluidsOpened = current;
            try {
                this.fluidTextures = FluidTextures.open(current.archive());
            } catch (IOException exception) {
                System.getLogger(ItemIconService.class.getName()).log(System.Logger.Level.WARNING,
                        "Unable to read fluid appearances from " + current.archive(), exception);
            }
        }
        if (this.fluidTextures == null) {
            return Optional.empty();
        }
        return this.fluids.computeIfAbsent(fluidId, id -> {
            try {
                return this.fluidTextures.texture(id);
            } catch (IOException exception) {
                return Optional.empty();
            }
        });
    }

    /** Sets where the model and tints of an item id come from; the project's captured pack catalog provides them. */
    public void setItemLookup(Function<String, Optional<CatalogIndex.ItemIcon>> lookup) {
        this.itemLookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * The model and tints the game draws an item id with, as captured for its default stack. Before a capture only the
     * conventional model is known, without tints.
     */
    public CatalogIndex.ItemIcon itemIcon(String itemId) {
        return this.itemLookup.apply(itemId).orElseGet(() -> new CatalogIndex.ItemIcon(itemModel(itemId), Map.of()));
    }

    /** The conventional inventory model of an item registry id: {@code ns:path} becomes {@code ns:item/path}. */
    public static String itemModel(String itemId) {
        int separator = itemId.indexOf(':');
        return separator < 0 ? "" : itemId.substring(0, separator) + ":item/" + itemId.substring(separator + 1);
    }

    /**
     * The blockstate, models and textures that draw a block and an item in the latest snapshot; either id may be
     * empty. Empty before a snapshot arrives.
     */
    public CompletableFuture<Optional<ModelAppearance>> appearance(String blockId, String itemModel) {
        if (this.closed || this.snapshot == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!openBackend()) return Optional.empty();
            try {
                return Optional.of(this.backend.appearance(blockId, itemModel));
            } catch (IOException | RuntimeException unreadable) {
                return Optional.empty();
            }
        }, this.worker);
    }

    /** Opens the renderer on the latest snapshot unless it already is. Worker thread only. */
    private boolean openBackend() {
        Snapshot current = this.snapshot;
        if (this.closed || current == null) {
            return false;
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
                return false;
            }
        }
        return true;
    }

    private Optional<BufferedImage> renderOnWorker(Key key) {
        if (!openBackend()) {
            return Optional.empty();
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
