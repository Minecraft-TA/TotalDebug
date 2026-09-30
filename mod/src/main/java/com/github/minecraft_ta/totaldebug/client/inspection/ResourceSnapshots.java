package com.github.minecraft_ta.totaldebug.client.inspection;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Instant;
import java.util.Arrays;
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
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Keeps immutable archives of the active pack stack's winning models and textures, plus every contributing atlas
 * definition, so Companion can draw item icons without Minecraft. Layer {@code n} holds the n-th contribution of an
 * additive atlas definition; ordinary resources only use layer 0. An archive is named by what it holds
 * ({@link IconArchiveKey}): packs whose files did not change since use the one captured for them, also in a later run,
 * and only other packs capture a new one. The most recent few are kept, so enabling a pack and disabling it again uses
 * the archive before; older ones are removed once no renderer holds them open.
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
    /** How many archives are kept, the most recently used first. */
    private static final int KEPT = 4;
    /** What a key holds besides the packs: the archive's layout, and the game whose resources it reads. */
    private static final String VERSION = "icon archive 1, Minecraft " + SharedConstants.getCurrentVersion().getName()
            + ", NeoForge " + FMLLoader.versionInfo().neoForgeVersion();
    static final String FLUID_APPEARANCES = "totaldebug/fluid-appearances.json";

    private final Path directory;
    private final Consumer<Path> publish;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("TotalDebug resource snapshot")
            .unstarted(task));
    private CompletableFuture<Path> pending;
    /**
     * What the pending archive is prepared for: the resource manager's packs and the fluids' looks. A reload opens the
     * packs anew, so other packs than those prepared for last mean the game read their files since.
     */
    private List<PackResources> pendingPacks = List.of();
    private byte[] pendingFluids = new byte[0];
    private long attemptedAt;

    public ResourceSnapshots(Path directory, Consumer<Path> publish) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /**
     * Publishes the archive of the current packs: the one kept for their key, or one captured now. The key is taken on
     * the snapshot thread, which may walk the packs' folders. A capture that failed is not tried again for ten seconds.
     * Client thread only.
     */
    public synchronized void prepare() {
        if (this.pending != null && this.pending.isCompletedExceptionally() && System.nanoTime() - this.attemptedAt < RETRY_NANOS) {
            return;
        }
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        List<PackResources> current = manager.listPacks().toList();
        byte[] fluids = fluidAppearances();
        // Asked again while the archive of the same resources is prepared, as by several inspections: that one is told.
        if (this.pending != null && !this.pending.isDone() && current.equals(this.pendingPacks) && Arrays.equals(fluids, this.pendingFluids)) {
            this.pending.thenAccept(this.publish);
            return;
        }
        List<IconArchiveKey.Part> parts = parts(current);
        // Only right after a reload does the resource manager hold what the files hold; a capture between reloads, as
        // after a texture put in place, may miss a namespace, overlay or filter added since, so it is not kept by key.
        boolean reloaded = !current.equals(this.pendingPacks);
        this.pendingPacks = current;
        this.pendingFluids = fluids;
        this.attemptedAt = System.nanoTime();
        this.pending = CompletableFuture.supplyAsync(() -> {
            try {
                long started = System.nanoTime();
                Optional<String> key = IconArchiveKey.of(VERSION, parts, fluids, Instant.now());
                Optional<Path> reused = key.map(name -> this.directory.resolve(name + ".zip")).filter(ResourceSnapshots::complete);
                if (reused.isPresent()) {
                    used(reused.get());
                    TotalDebug.LOGGER.info("Item icon archive unchanged; key taken in {} ms", (System.nanoTime() - started) / 1_000_000);
                    return reused.get();
                }
                Optional<String> kept = key.filter(ignored -> reloaded);
                Path archive = capture(manager, current, fluids, kept.orElseGet(() -> UUID.randomUUID().toString()));
                TotalDebug.LOGGER.info("Item icon archive captured in {} ms, {} KB{}", (System.nanoTime() - started) / 1_000_000,
                        Files.size(archive) / 1024, kept.isPresent() ? "" : key.isEmpty()
                                ? "; a pack's files could not be told, so it is not kept by key"
                                : "; captured between reloads, so it is not kept by key");
                return archive;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to prepare item icons: " + exception.getMessage(), exception);
            }
        }, this.worker);
        this.pending.whenComplete((ready, failure) -> {
            if (failure != null) {
                TotalDebug.LOGGER.warn("Unable to capture resources for Companion item icons", failure);
            }
        });
        this.pending.thenAccept(this.publish);
    }

    /**
     * Where the resources of {@code packs} come from: a {@code file/} pack's folder or zip, a {@code mod/} pack's mod
     * file, every mod file for the mods' pack; nothing for the game's own packs, whose files a version does not change.
     * The files of any other pack are not known. Client thread only.
     */
    private static List<IconArchiveKey.Part> parts(List<PackResources> packs) {
        Path folder = Minecraft.getInstance().getResourcePackDirectory();
        List<IconArchiveKey.Part> parts = new ArrayList<>();
        for (PackResources pack : packs) {
            String id = pack.packId();
            if (pack.knownPackInfo().filter(KnownPack::isVanilla).isPresent()) {
                parts.add(IconArchiveKey.Part.builtIn(id));
            } else if (id.startsWith("file/")) {
                parts.add(new IconArchiveKey.Part(id, List.of(folder.resolve(id.substring("file/".length())))));
            } else if (id.startsWith("mod/")) {
                // mod/a,b for a file of several mods; mod/a:resourcepacks/b for a pack a mod registers inside its file.
                IModFileInfo file = ModList.get().getModFileById(id.substring("mod/".length()).split("[,:]")[0]);
                parts.add(file == null ? IconArchiveKey.Part.unknown(id) : new IconArchiveKey.Part(id, resources(file)));
            } else if (id.equals("mod_resources")) {
                parts.add(new IconArchiveKey.Part(id, ModList.get().getModFiles().stream().flatMap(file -> resources(file).stream()).toList()));
            } else {
                parts.add(IconArchiveKey.Part.unknown(id));
            }
        }
        return parts;
    }

    /**
     * Where a mod file's resources are read: the jar itself; for a mod of folders, as in a development run, which may
     * merge several, everything the game reads through the jar it makes of them, its pack.mcmeta and overlays too.
     */
    private static List<Path> resources(IModFileInfo info) {
        Path file = info.getFile().getFilePath();
        return List.of(Files.isRegularFile(file) ? file : info.getFile().getSecureJar().getRootPath());
    }

    /**
     * Whether a kept archive was written whole: its fluids' looks, written last, are there. One that is not, as after a
     * crash of an earlier run, is removed and captured again.
     */
    private static boolean complete(Path archive) {
        if (!Files.isRegularFile(archive)) return false;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("layers/0/" + FLUID_APPEARANCES) != null) return true;
        } catch (IOException unreadable) {
            TotalDebug.LOGGER.warn("Icon archive {} cannot be read and is captured again: {}", archive, unreadable.getMessage());
        }
        try {
            Files.deleteIfExists(archive);
        } catch (IOException inUse) {
            TotalDebug.LOGGER.debug("Icon archive {} is still open", archive);
        }
        return false;
    }

    /** Marks {@code archive} as the most recently used, so it is kept and Companion restores it without a game. */
    private static void used(Path archive) {
        try {
            Files.setLastModifiedTime(archive, FileTime.from(Instant.now()));
        } catch (IOException unchanged) {
            TotalDebug.LOGGER.debug("Icon archive {} keeps its time", archive, unchanged);
        }
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

    private Path capture(ResourceManager manager, List<PackResources> expected, byte[] fluids, String name) throws IOException {
        Files.createDirectories(this.directory);
        trimArchives();
        if (retainedBytes() > MAX_RETAINED_BYTES) {
            throw new IOException("Older icon archives are still open; close Companion inspections and retry");
        }
        Path output = this.directory.resolve(name + ".zip");
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
        return output;
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

    /**
     * Before a capture, keeps the most recently used archives, fewer than {@link #KEPT}, while they leave room for one
     * more within {@link #MAX_RETAINED_BYTES}; a renderer may still hold an older one open until it switches snapshots.
     */
    private void trimArchives() throws IOException {
        List<Path> archives;
        try (Stream<Path> files = Files.list(this.directory)) {
            archives = files.filter(ResourceSnapshots::ownedArchive)
                    .sorted(Comparator.comparingLong(ResourceSnapshots::modified).reversed())
                    .toList();
        }
        long kept = 0;
        for (int index = 0; index < archives.size(); index++) {
            Path archive = archives.get(index);
            long size = Files.size(archive);
            if (index < KEPT - 1 && kept + size <= MAX_RETAINED_BYTES - MAX_ARCHIVE_BYTES) {
                kept += size;
                continue;
            }
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

    /** An archive named by its key, or, where a pack's files could not be told, by a random id. */
    private static boolean ownedArchive(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().matches("([0-9a-f]{64}|[0-9a-f-]{36})\\.zip");
    }

    @FunctionalInterface
    private interface ResourceOpener {
        InputStream open() throws IOException;
    }
}
