package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.tth05.jindex.ClassIndex;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Runs TotalDebug's production index-input preparation against this process's complete runtime classpath. */
public final class InitialIndexBenchmark {
    private InitialIndexBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        List<Path> sources = Arrays.stream(System.getProperty("java.class.path", "").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::exists)
                .filter(path -> Files.isDirectory(path) || isArchive(path))
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        Path workspace = Files.createTempDirectory("totaldebug-initial-index-benchmark-");
        try {
            long totalStarted = System.nanoTime();
            long prepareStarted = System.nanoTime();
            RuntimeClassIndex.PreparedIndexInputs inputs = RuntimeClassIndex.prepareIndexInputs(sources, workspace);
            long prepareMillis = elapsedMillis(prepareStarted);

            long buildStarted = System.nanoTime();
            try (ClassIndex index = ClassIndex.fromSources(
                    inputs.jarFiles().stream().map(Path::toString).toList(),
                    inputs.classFiles()
            )) {
                long buildMillis = elapsedMillis(buildStarted);
                requireClass(index, "java/lang", "String");
                requireClass(index, "net/minecraft/world/level/block", "Block");

                Path outputIndex = workspace.resolve("index");
                long saveStarted = System.nanoTime();
                index.saveToFile(outputIndex.toString());
                long saveMillis = elapsedMillis(saveStarted);

                System.out.printf(
                        "RESULT sources=%d jars=%d directClasses=%d prepareMs=%d buildMs=%d saveMs=%d totalMs=%d outputBytes=%d%n",
                        sources.size(),
                        inputs.jarFiles().size(),
                        inputs.classFiles().size(),
                        prepareMillis,
                        buildMillis,
                        saveMillis,
                        elapsedMillis(totalStarted),
                        Files.size(outputIndex)
                );
            }
        } finally {
            deleteTree(workspace);
        }
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".jar") || name.endsWith(".zip"));
    }

    private static void requireClass(ClassIndex index, String packageName, String className) {
        if (index.findClass(packageName, className) == null) {
            throw new IllegalStateException("Missing class " + packageName + "/" + className);
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
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
