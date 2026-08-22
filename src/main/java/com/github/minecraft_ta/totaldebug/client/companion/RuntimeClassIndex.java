package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import com.github.tth05.jindex.ClassIndex;
import io.github.classgraph.ClassGraph;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    synchronized boolean ensurePresent(Runnable beforeBuild) throws IOException {
        Objects.requireNonNull(beforeBuild, "beforeBuild");
        List<Path> sources = RuntimeSourceInventory.discover(
                TotalDebug.class,
                Block.class,
                ClassGraph.class,
                ClassIndex.class
        );
        String signature = calculateSignature(sources);
        if (signature.equals(this.ensuredSignature)) {
            return false;
        }

        Files.createDirectories(this.dataDirectory);
        Path indexFile = this.dataDirectory.resolve(INDEX_FILE_NAME);
        Path metadataFile = this.dataDirectory.resolve(METADATA_FILE_NAME);
        if (Files.isRegularFile(indexFile)
                && Files.isRegularFile(metadataFile)
                && signature.equals(Files.readString(metadataFile, StandardCharsets.UTF_8))) {
            this.ensuredSignature = signature;
            return false;
        }

        beforeBuild.run();
        TotalDebug.LOGGER.info("Building companion class index from {} runtime sources", sources.size());
        long started = System.nanoTime();
        Path workspace = Files.createTempDirectory(this.dataDirectory, ".class-index-");
        try {
            PreparedIndexInputs indexInputs = prepareIndexInputs(sources, workspace);
            if (indexInputs.jarFiles().isEmpty() && indexInputs.classFiles().isEmpty()) {
                throw new IOException("No runtime class files were available for the companion class index");
            }

            Path stagedIndex = workspace.resolve(INDEX_FILE_NAME);
            try (ClassIndex classIndex = ClassIndex.fromSources(
                    indexInputs.jarFiles().stream().map(Path::toString).toList(),
                    indexInputs.classFiles()
            )) {
                classIndex.saveToFile(stagedIndex.toString());
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
                    "Built companion class index from {} JAR inputs and {} direct class files in {} ms",
                    indexInputs.jarFiles().size(),
                    indexInputs.classFiles().size(),
                    (System.nanoTime() - started) / 1_000_000
            );
            return true;
        } catch (RuntimeException exception) {
            throw new IOException("Unable to build the companion class index", exception);
        } finally {
            deleteTree(workspace);
        }
    }

    Path indexFile() {
        return this.dataDirectory.resolve(INDEX_FILE_NAME);
    }

    static PreparedIndexInputs prepareIndexInputs(List<Path> sources, Path workspace) throws IOException {
        List<Path> jarFiles = new ArrayList<>();
        List<byte[]> classFiles = new ArrayList<>();
        int sourceNumber = 0;
        for (Path source : sources) {
            int currentSourceNumber = sourceNumber++;
            try {
                if (Files.isDirectory(source)) {
                    readClassDirectory(source, classFiles);
                } else {
                    prepareJarSource(source, workspace, currentSourceNumber, jarFiles);
                }
            } catch (IOException | RuntimeException exception) {
                throw new IOException(
                        "Unable to prepare runtime class source " + source.toUri()
                                + " using the " + source.getFileSystem().provider().getScheme() + " filesystem",
                        exception
                );
            }
        }

        readJdkClasses(classFiles);
        return new PreparedIndexInputs(List.copyOf(jarFiles), List.copyOf(classFiles));
    }

    static void prepareJarSource(
            Path sourceJar,
            Path workspace,
            int sourceNumber,
            List<Path> inputs
    ) throws IOException {
        Path indexInput = sourceJar;
        if (sourceJar.getFileSystem() != FileSystems.getDefault()) {
            indexInput = workspace.resolve("source-" + sourceNumber + ".jar");
            try (InputStream input = Files.newInputStream(sourceJar);
                 OutputStream output = Files.newOutputStream(indexInput)) {
                input.transferTo(output);
            }
        }

        inputs.add(indexInput);
        extractNestedJars(indexInput, workspace.resolve("nested-" + sourceNumber), inputs);
    }

    static int readClassDirectory(Path sourceDirectory, List<byte[]> classFiles) throws IOException {
        int initialSize = classFiles.size();
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = sourceDirectory.relativize(path).toString().replace('\\', '/');
                if (isIndexableClassEntry(entryName)) {
                    classFiles.add(Files.readAllBytes(path));
                }
            }
        }
        return classFiles.size() - initialSize;
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

    private static void readJdkClasses(List<byte[]> classFiles) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modules = jrt.getPath("/modules");
        try (Stream<Path> modulePaths = Files.list(modules)) {
            for (Path module : modulePaths.sorted().toList()) {
                try (Stream<Path> classes = Files.walk(module)) {
                    for (Path classFile : classes.filter(Files::isRegularFile).sorted().toList()) {
                        String entryName = module.relativize(classFile).toString().replace('\\', '/');
                        if (!isIndexableClassEntry(entryName) || !entries.add(entryName)) {
                            continue;
                        }
                        classFiles.add(Files.readAllBytes(classFile));
                    }
                }
            }
        }
    }

    record PreparedIndexInputs(List<Path> jarFiles, List<byte[]> classFiles) {
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
