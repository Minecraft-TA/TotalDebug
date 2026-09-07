package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class CompanionJavaRuntime {
    private static final int MINIMUM_JAVA_FEATURE = 21;
    private static final List<String> REQUIRED_MODULES = List.of(
            "java.base",
            "java.compiler",
            "java.datatransfer",
            "java.desktop",
            "java.logging",
            "java.net.http",
            "java.prefs",
            "java.sql",
            "java.transaction.xa",
            "java.xml",
            "jdk.attach",
            "jdk.compiler",
            "jdk.jdi",
            "jdk.unsupported",
            "jdk.zipfs"
    );

    private CompanionJavaRuntime() {
    }

    static Path resolveCurrentExecutable() throws IOException {
        Path executable = ProcessHandle.current()
                .info()
                .command()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .orElseThrow(() -> new IOException("The current Minecraft Java executable is unavailable"));

        validate(
                executable,
                Runtime.version().feature(),
                ModuleLayer.boot().modules().stream().map(Module::getName).collect(Collectors.toSet())
        );
        return executable;
    }

    static void validate(Path executable, int javaFeature, Set<String> availableModules) throws IOException {
        if (!Files.isRegularFile(executable)) {
            throw new IOException("The current Minecraft Java executable does not exist: " + executable);
        }
        if (javaFeature < MINIMUM_JAVA_FEATURE) {
            throw new IOException(
                    "TotalDebugCompanion requires Java " + MINIMUM_JAVA_FEATURE
                            + " or newer, but Minecraft is running Java " + javaFeature
            );
        }

        Set<String> missingModules = new LinkedHashSet<>(REQUIRED_MODULES);
        missingModules.removeAll(availableModules);
        if (!missingModules.isEmpty()) {
            throw new IOException(
                    "Minecraft's Java runtime cannot launch TotalDebugCompanion; missing modules: "
                            + String.join(", ", missingModules)
            );
        }
    }
}
