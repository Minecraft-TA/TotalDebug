package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import io.github.classgraph.ClassGraph;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
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
    record PublishedInventory(String id, Path file) {
    }

    private final Path dataDirectory;

    RuntimeInventoryPublisher(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    }

    PublishedInventory publish() throws IOException {
        List<Path> discovered = RuntimeSourceInventory.discover(TotalDebug.class, Block.class, ClassGraph.class);
        Map<Path, RuntimeInventory.RuntimeModule> runtimeModules = describeRuntimeModules(discovered);
        String id = calculateInventoryId(discovered, runtimeModules);
        Path inventories = this.dataDirectory.resolve("runtime-inventories");
        Path publishedDirectory = inventories.resolve(id);
        Path publishedFile = publishedDirectory.resolve(RuntimeInventory.FILE_NAME);
        if (Files.isRegularFile(publishedFile)) {
            RuntimeInventory existing = RuntimeInventory.read(publishedFile);
            if (!id.equals(existing.id())) {
                throw new IOException("Runtime inventory cache conflicts with " + publishedDirectory);
            }
            return new PublishedInventory(id, publishedFile);
        }

        Files.createDirectories(inventories);
        Path staged = Files.createTempDirectory(inventories, ".runtime-inventory-");
        try {
            List<RuntimeInventory.Source> sources = prepareSources(
                    discovered,
                    runtimeModules,
                    staged,
                    publishedDirectory
            );
            RuntimeInventory inventory = new RuntimeInventory(
                    id,
                    System.getProperty("java.runtime.version", "unknown"),
                    System.getProperty("java.home", "unknown"),
                    FMLLoader.isProduction(),
                    sources
            );
            inventory.write(staged.resolve(RuntimeInventory.FILE_NAME));
            try {
                Files.move(staged, publishedDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                RuntimeInventory winner = RuntimeInventory.read(publishedFile);
                if (!id.equals(winner.id())) {
                    throw exception;
                }
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
            List<Path> discovered,
            Map<Path, RuntimeInventory.RuntimeModule> runtimeModules,
            Path stagedDirectory,
            Path publishedDirectory
    ) throws IOException {
        List<RuntimeInventory.Source> sources = new ArrayList<>(discovered.size());
        for (int index = 0; index < discovered.size(); index++) {
            Path source = discovered.get(index);
            RuntimeInventory.SourceKind kind = Files.isDirectory(source)
                    ? RuntimeInventory.SourceKind.DIRECTORY
                    : RuntimeInventory.SourceKind.ARCHIVE;
            Path publishedPath = source;
            if (source.getFileSystem() != FileSystems.getDefault()) {
                String name = kind == RuntimeInventory.SourceKind.ARCHIVE
                        ? "source-" + index + ".jar"
                        : "source-" + index;
                Path stagedPath = stagedDirectory.resolve("sources").resolve(name);
                publishedPath = publishedDirectory.resolve("sources").resolve(name);
                if (kind == RuntimeInventory.SourceKind.ARCHIVE) {
                    Files.createDirectories(stagedPath.getParent());
                    try (InputStream input = Files.newInputStream(source);
                         OutputStream output = Files.newOutputStream(stagedPath)) {
                        input.transferTo(output);
                    }
                } else {
                    copyClassDirectory(source, stagedPath);
                }
            }
            RuntimeInventory.RuntimeModule runtimeModule = Objects.requireNonNull(
                    runtimeModules.get(source.toAbsolutePath().normalize()),
                    () -> "Runtime source has no module identity: " + source
            );
            sources.add(new RuntimeInventory.Source(
                    kind,
                    publishedPath,
                    source.toUri().toASCIIString(),
                    runtimeModule
            ));
        }
        return List.copyOf(sources);
    }

    static Map<Path, RuntimeInventory.RuntimeModule> describeRuntimeModules(List<Path> sources) {
        Map<Path, RuntimeInventory.RuntimeModule> modules = new LinkedHashMap<>();
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
            String id = ids.isEmpty() ? modFile.getModFileInfo().moduleName() : String.join("+", ids);
            String name = names.isEmpty() ? id : String.join(" + ", names);
            modules.put(
                    modFile.getFilePath().toAbsolutePath().normalize(),
                    new RuntimeInventory.RuntimeModule(id, name)
            );
        });
        for (Path source : sources) {
            Path normalized = source.toAbsolutePath().normalize();
            modules.computeIfAbsent(normalized, ignored -> fallbackModule(source));
        }
        return Map.copyOf(modules);
    }

    static RuntimeInventory.RuntimeModule fallbackModule(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        for (Path cursor = normalized.getParent(); cursor != null; cursor = cursor.getParent()) {
            if (cursor.getFileName() != null
                    && "build".equals(cursor.getFileName().toString())
                    && cursor.getParent() != null
                    && cursor.getParent().getFileName() != null) {
                String projectName = cursor.getParent().getFileName().toString();
                return new RuntimeInventory.RuntimeModule(projectName, projectName);
            }
        }
        String fileName = normalized.getFileName().toString();
        String moduleName = fileName.endsWith(".jar") || fileName.endsWith(".zip")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        return new RuntimeInventory.RuntimeModule(moduleName, moduleName);
    }

    private static void copyClassDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relativeName = source.relativize(file).toString().replace('\\', '/');
                if (!isIndexableClass(relativeName)) {
                    continue;
                }
                Path destination = target.resolve(relativeName);
                Files.createDirectories(destination.getParent());
                try (InputStream input = Files.newInputStream(file);
                     OutputStream output = Files.newOutputStream(destination)) {
                    input.transferTo(output);
                }
            }
        }
    }

    private static String calculateInventoryId(
            List<Path> sources,
            Map<Path, RuntimeInventory.RuntimeModule> runtimeModules
    ) throws IOException {
        MessageDigest digest = sha256();
        update(digest, Integer.toString(RuntimeInventory.FORMAT_VERSION));
        update(digest, System.getProperty("java.runtime.version", ""));
        update(digest, System.getProperty("java.home", ""));
        update(digest, Boolean.toString(FMLLoader.isProduction()));
        ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + "=" + mod.getVersion())
                .sorted()
                .forEach(value -> update(digest, value));
        for (Path source : sources) {
            RuntimeInventory.RuntimeModule runtimeModule = Objects.requireNonNull(
                    runtimeModules.get(source.toAbsolutePath().normalize()),
                    () -> "Runtime source has no module identity: " + source
            );
            update(digest, runtimeModule.id());
            update(digest, runtimeModule.displayName());
            update(digest, source.getFileSystem().provider().getScheme());
            update(digest, source.toUri().toASCIIString());
            if (Files.isDirectory(source)) {
                try (Stream<Path> paths = Files.walk(source)) {
                    for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        String relativeName = source.relativize(file).toString().replace('\\', '/');
                        if (!isIndexableClass(relativeName)) {
                            continue;
                        }
                        update(digest, relativeName);
                        update(digest, Long.toString(Files.size(file)));
                        update(digest, Long.toString(Files.getLastModifiedTime(file).toMillis()));
                    }
                }
            } else {
                update(digest, Long.toString(Files.size(source)));
                update(digest, Long.toString(Files.getLastModifiedTime(source).toMillis()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isIndexableClass(String name) {
        return name.endsWith(".class")
                && !name.equals("module-info.class")
                && !name.endsWith("/module-info.class");
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
