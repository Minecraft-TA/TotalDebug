package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.runtime.PreparedRuntimeSources;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

final class RuntimeInventoryPublisher {
    private static final RuntimeInventory.RuntimeModule MINECRAFT_MODULE = new RuntimeInventory.RuntimeModule(
            "minecraft",
            "Minecraft",
            RuntimeInventory.ModuleKind.PLATFORM
    );
    private static final RuntimeInventory.RuntimeModule NEOFORGE_MODULE = new RuntimeInventory.RuntimeModule(
            "neoforge",
            "NeoForge",
            RuntimeInventory.ModuleKind.PLATFORM
    );
    private static final RuntimeInventory.RuntimeModule MERGED_PLATFORM_MODULE = new RuntimeInventory.RuntimeModule(
            "minecraft+neoforge",
            "Minecraft + NeoForge",
            RuntimeInventory.ModuleKind.PLATFORM
    );

    record PublishedInventory(String id, Path file) {
    }

    private final Path dataDirectory;

    RuntimeInventoryPublisher(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    }

    PublishedInventory publish() throws IOException {
        PreparedRuntimeSources prepared = TotalDebug.get().runtimeSources();
        Map<Path, RuntimeInventory.RuntimeModule> runtimeModules = describeRuntimeModules(prepared);
        String id = calculateInventoryId(prepared, runtimeModules);
        Path publishedDirectory = this.dataDirectory.resolve("runtime-inventory");
        Path publishedFile = publishedDirectory.resolve(RuntimeInventory.FILE_NAME);
        if (matchesPublishedInventory(publishedFile, id)) {
            return new PublishedInventory(id, publishedFile);
        }

        Files.createDirectories(this.dataDirectory);
        Path staged = Files.createTempDirectory(this.dataDirectory, ".runtime-inventory-");
        try {
            List<RuntimeInventory.Source> sources = prepareSources(
                    prepared,
                    runtimeModules
            );
            RuntimeInventory inventory = new RuntimeInventory(
                    id,
                    System.getProperty("java.runtime.version", "unknown"),
                    System.getProperty("java.home", "unknown"),
                    FMLLoader.isProduction(),
                    sources
            );
            inventory.write(staged.resolve(RuntimeInventory.FILE_NAME));
            deleteTree(publishedDirectory);
            try {
                Files.move(staged, publishedDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("The TotalDebug data directory does not support atomic runtime inventory updates", exception);
            }
            RuntimeInventory.read(publishedFile);
            return new PublishedInventory(id, publishedFile);
        } finally {
            deleteTree(staged);
        }
    }

    static List<RuntimeInventory.Source> prepareSources(
            PreparedRuntimeSources prepared,
            Map<Path, RuntimeInventory.RuntimeModule> runtimeModules
    ) {
        List<RuntimeInventory.Source> sources = new ArrayList<>(prepared.sources().size());
        for (PreparedRuntimeSources.Source source : prepared.sources()) {
            RuntimeInventory.SourceKind kind = Files.isDirectory(source.path())
                    ? RuntimeInventory.SourceKind.DIRECTORY
                    : RuntimeInventory.SourceKind.ARCHIVE;
            RuntimeInventory.RuntimeModule runtimeModule = Objects.requireNonNull(
                    runtimeModules.get(source.original().path()),
                    () -> "Runtime source has no module identity: " + source
            );
            sources.add(new RuntimeInventory.Source(
                    kind,
                    source.path(),
                    source.logicalUri(),
                    runtimeModule
            ));
        }
        return List.copyOf(sources);
    }

    static Map<Path, RuntimeInventory.RuntimeModule> describeRuntimeModules(PreparedRuntimeSources prepared) throws IOException {
        List<Path> sources = prepared.sources().stream().map(source -> source.original().path()).toList();
        Map<Path, RuntimeInventory.RuntimeModule> modules = new LinkedHashMap<>();
        Map<String, RuntimeInventory.RuntimeModule> namedModules = new LinkedHashMap<>();
        ModList.get().forEachModFile(modFile -> {
            List<String> ids = modFile.getModInfos().stream()
                    .map(mod -> mod.getModId())
                    .distinct()
                    .sorted()
                    .toList();
            List<String> names = modFile.getModInfos().stream()
                    .map(mod -> mod.getDisplayName())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            String moduleName = modFile.getModFileInfo().moduleName();
            RuntimeInventory.RuntimeModule module = describeModule(ids, names, moduleName);
            modules.put(modFile.getFilePath().toAbsolutePath().normalize(), module);
            namedModules.put(moduleName, module);
        });
        for (PreparedRuntimeSources.Source source : prepared.sources()) {
            RuntimeSourceInventory.Source original = source.original();
            modules.computeIfAbsent(original.path(), ignored -> original.moduleName() == null
                    ? fallbackModule(original.path())
                    : namedModules.getOrDefault(original.moduleName(), describeModule(
                            List.of(), List.of(), original.moduleName()
                    )));
        }
        Map<Class<?>, Path> anchorSources = RuntimeSourceInventory.sourcesContaining(
                sources,
                TotalDebug.class,
                Block.class,
                FMLLoader.class
        );
        Path totalDebugSource = anchorSources.get(TotalDebug.class);
        if (totalDebugSource != null) {
            modules.put(
                    totalDebugSource,
                    new RuntimeInventory.RuntimeModule("total_debug", "TotalDebug", RuntimeInventory.ModuleKind.MOD)
            );
        }
        Path minecraftSource = anchorSources.get(Block.class);
        Path neoforgeSource = anchorSources.get(FMLLoader.class);
        assignPlatformModules(modules, minecraftSource, neoforgeSource);
        return Map.copyOf(modules);
    }

    static RuntimeInventory.RuntimeModule describeModule(
            List<String> ids,
            List<String> names,
            String moduleName
    ) {
        List<String> moduleIds = List.copyOf(ids);
        String id = moduleIds.isEmpty() ? moduleName : String.join("+", moduleIds);
        String name = names.isEmpty() ? id : String.join(" + ", names);
        RuntimeInventory.ModuleKind kind;
        if (moduleIds.contains("minecraft") && moduleIds.contains("neoforge")) {
            return MERGED_PLATFORM_MODULE;
        } else if (moduleIds.contains("minecraft")) {
            return MINECRAFT_MODULE;
        } else if (moduleIds.contains("neoforge")) {
            return NEOFORGE_MODULE;
        } else if (moduleIds.isEmpty()) {
            kind = RuntimeInventory.ModuleKind.LIBRARY;
        } else {
            kind = RuntimeInventory.ModuleKind.MOD;
        }
        return new RuntimeInventory.RuntimeModule(id, name, kind);
    }

    static RuntimeInventory.RuntimeModule fallbackModule(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        for (Path cursor = normalized.getParent(); cursor != null; cursor = cursor.getParent()) {
            if (cursor.getFileName() != null
                    && "build".equals(cursor.getFileName().toString())
                    && cursor.getParent() != null
                    && cursor.getParent().getFileName() != null) {
                String projectName = cursor.getParent().getFileName().toString();
                return new RuntimeInventory.RuntimeModule(
                        projectName,
                        projectName,
                        RuntimeInventory.ModuleKind.LIBRARY
                );
            }
        }
        String fileName = normalized.getFileName().toString();
        String moduleName = fileName.endsWith(".jar") || fileName.endsWith(".zip")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        return new RuntimeInventory.RuntimeModule(
                moduleName,
                moduleName,
                RuntimeInventory.ModuleKind.LIBRARY
        );
    }

    static boolean matchesPublishedInventory(Path publishedFile, String expectedId) {
        if (!Files.isRegularFile(publishedFile)) {
            return false;
        }
        try {
            return expectedId.equals(RuntimeInventory.read(publishedFile).id());
        } catch (IOException invalidGeneratedInventory) {
            return false;
        }
    }

    static void assignPlatformModules(
            Map<Path, RuntimeInventory.RuntimeModule> modules,
            Path minecraftSource,
            Path neoforgeSource
    ) {
        Path normalizedMinecraft = normalize(minecraftSource);
        Path normalizedNeoForge = normalize(neoforgeSource);
        boolean merged = normalizedMinecraft != null
                && (normalizedMinecraft.equals(normalizedNeoForge)
                || NEOFORGE_MODULE.equals(modules.get(normalizedMinecraft))
                || MERGED_PLATFORM_MODULE.equals(modules.get(normalizedMinecraft)));
        if (normalizedMinecraft != null) {
            modules.put(normalizedMinecraft, merged ? MERGED_PLATFORM_MODULE : MINECRAFT_MODULE);
        }
        if (normalizedNeoForge != null) {
            modules.put(normalizedNeoForge, merged ? MERGED_PLATFORM_MODULE : NEOFORGE_MODULE);
        }
    }

    private static Path normalize(Path source) {
        return source == null ? null : source.toAbsolutePath().normalize();
    }

    private static String calculateInventoryId(
            PreparedRuntimeSources prepared,
            Map<Path, RuntimeInventory.RuntimeModule> runtimeModules
    ) {
        MessageDigest digest = sha256();
        update(digest, Integer.toString(RuntimeInventory.FORMAT_VERSION));
        update(digest, prepared.id());
        update(digest, System.getProperty("java.runtime.version", ""));
        update(digest, System.getProperty("java.home", ""));
        update(digest, Boolean.toString(FMLLoader.isProduction()));
        ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + "=" + mod.getVersion())
                .sorted()
                .forEach(value -> update(digest, value));
        for (PreparedRuntimeSources.Source source : prepared.sources()) {
            RuntimeInventory.RuntimeModule runtimeModule = Objects.requireNonNull(
                    runtimeModules.get(source.original().path()),
                    () -> "Runtime source has no module identity: " + source
            );
            update(digest, runtimeModule.id());
            update(digest, runtimeModule.displayName());
            update(digest, runtimeModule.kind().name());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
