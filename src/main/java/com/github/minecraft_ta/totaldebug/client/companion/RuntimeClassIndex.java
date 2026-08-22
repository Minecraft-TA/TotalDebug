package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.jindex.ClassIndex;
import io.github.classgraph.ClassGraph;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class RuntimeClassIndex {
    private static final String INDEX_FILE_NAME = "index";
    private static final String METADATA_FILE_NAME = "index.meta";
    private static final String INDEX_FORMAT_VERSION = "1";

    private final Path dataDirectory;
    private String ensuredSignature;

    RuntimeClassIndex(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
    }

    synchronized void ensurePresent() throws IOException {
        List<Path> sources = discoverRuntimeSources();
        String signature = calculateSignature(sources);
        if (signature.equals(this.ensuredSignature)) {
            return;
        }

        Files.createDirectories(this.dataDirectory);
        Path indexFile = this.dataDirectory.resolve(INDEX_FILE_NAME);
        Path metadataFile = this.dataDirectory.resolve(METADATA_FILE_NAME);
        if (Files.isRegularFile(indexFile)
                && Files.isRegularFile(metadataFile)
                && signature.equals(Files.readString(metadataFile, StandardCharsets.UTF_8))) {
            this.ensuredSignature = signature;
            return;
        }

        TotalDebug.LOGGER.info("Building companion class index from {} runtime sources", sources.size());
        long started = System.nanoTime();
        Path workspace = Files.createTempDirectory(this.dataDirectory, ".class-index-");
        try {
            List<Path> indexInputs = prepareJarInputs(sources, workspace);
            if (indexInputs.isEmpty()) {
                throw new IOException("No runtime class files were available for the companion class index");
            }

            Path stagedIndex = workspace.resolve(INDEX_FILE_NAME);
            ClassIndex classIndex = ClassIndex.fromJars(indexInputs.stream().map(Path::toString).toList());
            try {
                classIndex.saveToFile(stagedIndex.toString());
            } finally {
                classIndex.destroy();
            }
            if (!Files.isRegularFile(stagedIndex)) {
                throw new IOException("jindex did not create the staged companion class index");
            }

            Path stagedMetadata = workspace.resolve(METADATA_FILE_NAME);
            Files.writeString(stagedMetadata, signature, StandardCharsets.UTF_8);
            atomicReplace(stagedIndex, indexFile);
            atomicReplace(stagedMetadata, metadataFile);
            this.ensuredSignature = signature;
            TotalDebug.LOGGER.info(
                    "Built companion class index from {} JAR inputs in {} ms",
                    indexInputs.size(),
                    (System.nanoTime() - started) / 1_000_000
            );
        } catch (RuntimeException exception) {
            throw new IOException("Unable to build the companion class index", exception);
        } finally {
            deleteTree(workspace);
        }
    }

    Path indexFile() {
        return this.dataDirectory.resolve(INDEX_FILE_NAME);
    }

    private static List<Path> discoverRuntimeSources() throws IOException {
        Set<Path> sources = new LinkedHashSet<>();
        Consumer<Path> addSource = path -> {
            if (path != null) {
                sources.add(path.toAbsolutePath().normalize());
            }
        };

        ModList.get().forEachModFile(modFile -> addSource.accept(modFile.getFilePath()));
        addModuleLayerSources(sources, TotalDebug.class.getModule().getLayer(), new LinkedHashSet<>());
        addJavaClasspathSources(sources);
        addCodeSource(sources, TotalDebug.class);
        addCodeSource(sources, Block.class);
        addCodeSource(sources, ClassGraph.class);
        addCodeSource(sources, ClassIndex.class);

        List<Path> existingSources = new ArrayList<>();
        for (Path source : sources) {
            if (!Files.exists(source)) {
                throw new IOException("Runtime class source does not exist: " + source);
            }
            if (Files.isDirectory(source) || isJar(source)) {
                existingSources.add(source);
            }
        }
        existingSources.sort(Comparator.comparing(Path::toString));
        return List.copyOf(existingSources);
    }

    private static void addModuleLayerSources(
            Set<Path> sources,
            ModuleLayer layer,
            Set<ModuleLayer> visitedLayers
    ) throws IOException {
        if (layer == null || !visitedLayers.add(layer)) {
            return;
        }
        for (ResolvedModule module : layer.configuration().modules()) {
            URI location = module.reference().location().orElse(null);
            if (location == null || "jrt".equalsIgnoreCase(location.getScheme())) {
                continue;
            }
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                continue;
            }
            try {
                sources.add(Path.of(location).toAbsolutePath().normalize());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid module location for " + module.name() + ": " + location, exception);
            }
        }
        for (ModuleLayer parent : layer.parents()) {
            addModuleLayerSources(sources, parent, visitedLayers);
        }
    }

    private static void addJavaClasspathSources(Set<Path> sources) {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                sources.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
    }

    private static void addCodeSource(Set<Path> sources, Class<?> anchor) throws IOException {
        CodeSource codeSource = anchor.getProtectionDomain() == null
                ? null
                : anchor.getProtectionDomain().getCodeSource();
        URL location = codeSource == null ? null : codeSource.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return;
        }
        try {
            sources.add(Path.of(location.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid code-source location for " + anchor.getName() + ": " + location, exception);
        }
    }

    private static List<Path> prepareJarInputs(List<Path> sources, Path workspace) throws IOException {
        List<Path> inputs = new ArrayList<>();
        int sourceNumber = 0;
        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                Path packedSource = workspace.resolve("source-" + sourceNumber++ + ".jar");
                if (packClassDirectory(source, packedSource)) {
                    inputs.add(packedSource);
                }
            } else {
                inputs.add(source);
                extractNestedJars(source, workspace.resolve("nested-" + sourceNumber++), inputs);
            }
        }

        Path jdkClasses = workspace.resolve("jdk-classes.jar");
        packJdkClasses(jdkClasses);
        inputs.add(jdkClasses);
        return inputs;
    }

    static boolean packClassDirectory(Path sourceDirectory, Path outputJar) throws IOException {
        boolean wroteClass = false;
        Set<String> entries = new LinkedHashSet<>();
        try (OutputStream output = Files.newOutputStream(outputJar);
             ZipOutputStream zip = new ZipOutputStream(output);
             Stream<Path> paths = Files.walk(sourceDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = sourceDirectory.relativize(path).toString().replace('\\', '/');
                if (!isIndexableClassEntry(entryName) || !entries.add(entryName)) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
                wroteClass = true;
            }
        }
        if (!wroteClass) {
            Files.deleteIfExists(outputJar);
        }
        return wroteClass;
    }

    private static void extractNestedJars(Path sourceJar, Path outputDirectory, List<Path> inputs) throws IOException {
        try (ZipFile zip = new ZipFile(sourceJar.toFile())) {
            int nestedNumber = 0;
            for (ZipEntry entry : zip.stream()
                    .filter(candidate -> !candidate.isDirectory() && candidate.getName().endsWith(".jar"))
                    .toList()) {
                Files.createDirectories(outputDirectory);
                Path nestedJar = outputDirectory.resolve("nested-" + nestedNumber++ + ".jar");
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, nestedJar);
                }
                inputs.add(nestedJar);
            }
        }
    }

    private static void packJdkClasses(Path outputJar) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modules = jrt.getPath("/modules");
        try (OutputStream output = Files.newOutputStream(outputJar);
             ZipOutputStream zip = new ZipOutputStream(output);
             Stream<Path> modulePaths = Files.list(modules)) {
            for (Path module : modulePaths.sorted().toList()) {
                try (Stream<Path> classes = Files.walk(module)) {
                    for (Path classFile : classes.filter(Files::isRegularFile).sorted().toList()) {
                        String entryName = module.relativize(classFile).toString().replace('\\', '/');
                        if (!isIndexableClassEntry(entryName) || !entries.add(entryName)) {
                            continue;
                        }
                        zip.putNextEntry(new ZipEntry(entryName));
                        Files.copy(classFile, zip);
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    private static boolean isIndexableClassEntry(String entryName) {
        return entryName.endsWith(".class")
                && !entryName.equals("module-info.class")
                && !entryName.endsWith("/module-info.class");
    }

    private static String calculateSignature(List<Path> sources) throws IOException {
        MessageDigest digest = sha256();
        updateDigest(digest, INDEX_FORMAT_VERSION);
        updateDigest(digest, System.getProperty("java.runtime.version", ""));
        updateDigest(digest, Boolean.toString(FMLLoader.isProduction()));
        ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + "=" + mod.getVersion())
                .sorted()
                .forEach(value -> updateDigest(digest, value));

        for (Path source : sources) {
            updateDigest(digest, source.toString());
            if (Files.isRegularFile(source)) {
                updateDigest(digest, Long.toString(Files.size(source)));
                updateDigest(digest, Long.toString(Files.getLastModifiedTime(source).toMillis()));
            } else {
                updateClassDirectoryDigest(digest, source);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void updateClassDirectoryDigest(MessageDigest digest, Path source) throws IOException {
        try (Stream<Path> classes = Files.walk(source)) {
            for (Path classFile : classes
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList()) {
                updateDigest(digest, source.relativize(classFile).toString());
                updateDigest(digest, classFile);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("This JVM does not provide SHA-256", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void updateDigest(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        digest.update((byte) 0);
    }

    private static boolean isJar(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".jar") || name.endsWith(".zip"));
    }

    private static void atomicReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("The companion data directory does not support atomic index updates", exception);
        }
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
