package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders standard Minecraft item models without starting Minecraft or an OpenGL context.
 *
 * <p>Resource roots are pack directories, JARs, or ZIPs in low-to-high priority order. The
 * caller supplies the already-resolved model identifier and any runtime tint colors. Model
 * predicates, mod code, custom model loaders, and custom item renderers are intentionally not
 * executed by this backend.
 */
public final class ItemRenderBackend implements AutoCloseable {

    private static final Pattern ITEM_MODEL_RESOURCE = Pattern.compile(
            "^assets/([^/]+)/models/(item/.+)\\.json$"
    );

    private final ResourcePackStack resources;
    private final ItemModelRepository models;
    private final SoftwareItemRenderer renderer;
    private final RenderCache<ItemRenderRequest, BufferedImage> renderCache = new RenderCache<>(
            8L * 1024 * 1024, image -> (long) image.getWidth() * image.getHeight());
    private boolean closed;

    private ItemRenderBackend(ResourcePackStack resources) {
        this.resources = resources;
        this.models = new ItemModelRepository(resources);
        this.renderer = new SoftwareItemRenderer(this.models);
    }

    public static ItemRenderBackend open(List<Path> resourceRoots) throws IOException {
        Objects.requireNonNull(resourceRoots, "resourceRoots");
        return openResourceRoots(resourceRoots.stream().map(ItemRenderResourceRoot::new).toList());
    }

    /** Opens explicit roots, including resource packs stored below a directory inside an archive. */
    public static ItemRenderBackend openResourceRoots(List<ItemRenderResourceRoot> resourceRoots) throws IOException {
        return new ItemRenderBackend(ResourcePackStack.open(resourceRoots));
    }

    public static ItemRenderBackend open(Path... resourceRoots) throws IOException {
        Objects.requireNonNull(resourceRoots, "resourceRoots");
        return open(List.of(resourceRoots));
    }

    /** Returns a caller-owned ARGB image. Mutating it cannot corrupt the backend cache. */
    public synchronized BufferedImage render(ItemRenderRequest request) throws IOException {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        BufferedImage cached = this.renderCache.get(request);
        if (cached == null) {
            cached = this.renderer.render(request);
            this.renderCache.put(request, cached);
        }
        return copy(cached);
    }

    /** The files and textures that draw a block and an item; either id may be empty. */
    public synchronized ModelAppearance appearance(String blockId, String itemModel) throws IOException {
        ensureOpen();
        return ModelAppearance.resolve(this.resources, blockId, itemModel);
    }

    /** Finds every item-model JSON visible in the supplied resource roots. */
    public synchronized List<ItemModelId> discoverItemModels() throws IOException {
        ensureOpen();
        List<ItemModelId> models = new ArrayList<>();
        for (String resource : this.resources.listResources("assets/", ".json")) {
            Matcher matcher = ITEM_MODEL_RESOURCE.matcher(resource);
            if (matcher.matches()) {
                models.add(new ItemModelId(matcher.group(1), matcher.group(2)));
            }
        }
        models.sort(ItemModelId::compareTo);
        return List.copyOf(models);
    }

    /**
     * Renders every request and captures per-item failures instead of stopping at the first one.
     * Successful images belong to the caller and may be painted directly or encoded later.
     */
    public synchronized ItemRenderBatchResult renderBatch(Collection<ItemRenderRequest> requests) {
        return renderBatch(requests, ItemRenderBatchOptions.RETAIN_IMAGES, entry -> {
        });
    }

    /** Runs a callback after each entry, which is useful for progress and streaming output. */
    public synchronized ItemRenderBatchResult renderBatch(
            Collection<ItemRenderRequest> requests,
            Consumer<ItemRenderBatchResult.Entry> entryConsumer
    ) {
        return renderBatch(requests, ItemRenderBatchOptions.RETAIN_IMAGES, entryConsumer);
    }

    public synchronized ItemRenderBatchResult renderBatch(
            Collection<ItemRenderRequest> requests,
            ItemRenderBatchOptions options
    ) {
        return renderBatch(requests, options, entry -> {
        });
    }

    /**
     * Runs a configurable batch. Diagnostic batches skip the final-image cache and discard
     * successful pixels while retaining parsed models and decoded textures.
     */
    public synchronized ItemRenderBatchResult renderBatch(
            Collection<ItemRenderRequest> requests,
            ItemRenderBatchOptions options,
            Consumer<ItemRenderBatchResult.Entry> entryConsumer
    ) {
        ensureOpen();
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(entryConsumer, "entryConsumer");
        List<ItemRenderRequest> copiedRequests = requests.stream()
                .map(request -> Objects.requireNonNull(request, "render request"))
                .toList();

        long batchStart = System.nanoTime();
        List<ItemRenderBatchResult.Entry> entries = new ArrayList<>(copiedRequests.size());
        for (ItemRenderRequest request : copiedRequests) {
            long entryStart = System.nanoTime();
            ItemRenderBatchResult.Entry entry;
            try {
                BufferedImage image = options.retainImages()
                        ? render(request)
                        : this.renderer.render(request);
                entry = new ItemRenderBatchResult.Entry(
                        request,
                        options.retainImages() ? image : null,
                        null,
                        visiblePixels(image),
                        elapsedNanos(entryStart)
                );
            } catch (Exception failure) {
                entry = new ItemRenderBatchResult.Entry(
                        request,
                        null,
                        failure,
                        0,
                        elapsedNanos(entryStart)
                );
            }
            entries.add(entry);
            entryConsumer.accept(entry);
        }
        return new ItemRenderBatchResult(entries, elapsedNanos(batchStart));
    }

    public synchronized void clearCaches() {
        ensureOpen();
        this.renderCache.clear();
        this.models.clearCaches();
    }

    @Override
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.renderCache.clear();
        this.models.clearCaches();
        this.resources.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Item render backend is closed");
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        source.copyData(copy.getRaster());
        return copy;
    }

    private static long elapsedNanos(long start) {
        return Math.max(0L, System.nanoTime() - start);
    }

    private static int visiblePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
