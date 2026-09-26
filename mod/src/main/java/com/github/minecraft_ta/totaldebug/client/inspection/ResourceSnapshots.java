package com.github.minecraft_ta.totaldebug.client.inspection;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Keeps one immutable archive of the active pack stack's winning models and textures, plus every contributing atlas
 * definition, so Companion can draw item icons without Minecraft. Layer {@code n} holds the n-th contribution of an
 * additive atlas definition; ordinary resources only use layer 0. A new archive is captured when the pack stack
 * changes. Older archives are removed once no renderer holds them open.
 *
 * <p>Fluid appearances come from mod code rather than resources, so the snapshot also records each fluid's still
 * texture, tint and light properties in {@value #FLUID_APPEARANCES}, the format Companion's renderer reads.</p>
 */
public final class ResourceSnapshots {
    private static final long MAX_RESOURCE_BYTES = 16L * 1024 * 1024;
    private static final long MAX_METADATA_BYTES = 1024L * 1024;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_RETAINED_BYTES = 1536L * 1024 * 1024;
    private static final long RETRY_NANOS = 10_000_000_000L;
    static final String FLUID_APPEARANCES = "totaldebug/fluid-appearances.json";

    private record Ready(Path archive, int layers) {
    }

    private final Path directory;
    private final BiConsumer<String, Integer> publish;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("TotalDebug resource snapshot")
            .unstarted(task));
    private List<PackResources> packs = List.of();
    private CompletableFuture<Ready> pending;
    private long attemptedAt;

    public ResourceSnapshots(Path directory, BiConsumer<String, Integer> publish) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /** Publishes the current snapshot, capturing it first when the pack stack changed. Client thread only. */
    public synchronized void prepare() {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        List<PackResources> current = manager.listPacks().toList();
        boolean retry = this.pending != null && this.pending.isCompletedExceptionally()
                && System.nanoTime() - this.attemptedAt > RETRY_NANOS;
        if (this.pending == null || !current.equals(this.packs) || retry) {
            this.packs = current;
            this.attemptedAt = System.nanoTime();
            byte[] fluids = fluidAppearances();
            this.pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return capture(manager, current, fluids);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to prepare item icons: " + exception.getMessage(), exception);
                }
            }, this.worker);
            this.pending.whenComplete((ready, failure) -> {
                if (failure != null) {
                    TotalDebug.LOGGER.warn("Unable to capture resources for Companion item icons", failure);
                }
            });
        }
        this.pending.thenAccept(ready -> this.publish.accept(ready.archive().toString(), ready.layers()));
    }

    /** Reads every fluid's client appearance. Client thread only, since mod extensions are client state. */
    private static byte[] fluidAppearances() {
        JsonObject fluids = new JsonObject();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) {
                continue;
            }
            try {
                IClientFluidTypeExtensions client = IClientFluidTypeExtensions.of(fluid);
                ResourceLocation still = client.getStillTexture();
                if (still == null) {
                    continue;
                }
                JsonObject appearance = new JsonObject();
                appearance.addProperty("stillTexture", still.toString());
                appearance.addProperty("tint", String.format(Locale.ROOT, "%08X", client.getTintColor()));
                appearance.addProperty("lightLevel", Math.clamp(fluid.getFluidType().getLightLevel(), 0, 15));
                appearance.addProperty("lighterThanAir", fluid.getFluidType().isLighterThanAir());
                fluids.add(BuiltInRegistries.FLUID.getKey(fluid).toString(), appearance);
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("No client appearance for fluid {}", BuiltInRegistries.FLUID.getKey(fluid),
                        exception);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.add("fluids", fluids);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Ready capture(ResourceManager manager, List<PackResources> expected, byte[] fluids) throws IOException {
        Files.createDirectories(this.directory);
        trimArchives();
        if (retainedBytes() > MAX_RETAINED_BYTES) {
            throw new IOException("Older icon archives are still open; close Companion inspections and retry");
        }
        Path output = this.directory.resolve(UUID.randomUUID() + ".zip");
        int[] layers = {1};
        AtomicFiles.replace(output, staged -> {
            try (var zip = new ZipOutputStream(Files.newOutputStream(staged))) {
                zip.setLevel(1);
                long[] total = {0};
                Set<String> written = new HashSet<>();
                // Blockstates choose a block's models, so a definition page can show how the block is drawn.
                for (String folder : List.of("blockstates", "models", "textures", "atlases")) {
                    for (Map.Entry<ResourceLocation, List<Resource>> entry
                            : manager.listResourceStacks(folder, id -> true).entrySet()) {
                        requireUnchanged(manager, expected);
                        // Atlas definitions combine across packs; everything else uses the winning resource.
                        List<Resource> retained = folder.equals("atlases")
                                ? entry.getValue()
                                : List.of(entry.getValue().getLast());
                        layers[0] = Math.max(layers[0], retained.size());
                        ResourceLocation id = entry.getKey();
                        for (int layer = 0; layer < retained.size(); layer++) {
                            write(zip, written, total, path(layer, id, ""), retained.get(layer)::open,
                                    MAX_RESOURCE_BYTES);
                        }
                        Optional<Resource> meta = manager.getResource(id.withPath(id.getPath() + ".mcmeta"));
                        // Metadata from a lower pack does not describe an overriding image.
                        if (meta.isPresent() && expected.indexOf(meta.get().source())
                                >= expected.indexOf(entry.getValue().getLast().source())) {
                            write(zip, written, total, path(0, id, ".mcmeta"), meta.get()::open, MAX_METADATA_BYTES);
                        }
                    }
                }
                requireUnchanged(manager, expected);
                write(zip, written, total, "layers/0/" + FLUID_APPEARANCES,
                        () -> new ByteArrayInputStream(fluids), MAX_METADATA_BYTES * 4);
            }
        });
        return new Ready(output, layers[0]);
    }

    private static String path(int layer, ResourceLocation id, String suffix) {
        return "layers/" + layer + "/assets/" + id.getNamespace() + "/" + id.getPath() + suffix;
    }

    private static void write(
            ZipOutputStream zip,
            Set<String> written,
            long[] total,
            String path,
            ResourceOpener opener,
            long limit
    ) throws IOException {
        if (!written.add(path)) {
            return;
        }
        byte[] bytes;
        try (InputStream input = opener.open()) {
            bytes = input.readNBytes((int) limit + 1);
        }
        total[0] += bytes.length;
        if (bytes.length > limit || total[0] > MAX_ARCHIVE_BYTES) {
            throw new IOException("Resource " + path + " exceeds the icon archive size limit");
        }
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void requireUnchanged(ResourceManager manager, List<PackResources> expected) throws IOException {
        if (!expected.equals(manager.listPacks().toList())) {
            throw new IOException("Resource packs changed during capture");
        }
    }

    private long retainedBytes() throws IOException {
        try (Stream<Path> files = Files.list(this.directory)) {
            return files.filter(ResourceSnapshots::ownedArchive).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException exception) {
                    return MAX_ARCHIVE_BYTES;
                }
            }).sum();
        }
    }

    /** Keeps the newest archive; a renderer may still hold older ones open until it switches snapshots. */
    private void trimArchives() throws IOException {
        List<Path> archives;
        try (Stream<Path> files = Files.list(this.directory)) {
            archives = files.filter(ResourceSnapshots::ownedArchive)
                    .sorted(Comparator.comparingLong(ResourceSnapshots::modified).reversed())
                    .toList();
        }
        for (Path archive : archives.subList(Math.min(1, archives.size()), archives.size())) {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException inUse) {
                TotalDebug.LOGGER.debug("Icon archive {} is still open", archive);
            }
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static boolean ownedArchive(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().matches("[0-9a-f-]{36}\\.zip");
    }

    @FunctionalInterface
    private interface ResourceOpener {
        InputStream open() throws IOException;
    }
}
