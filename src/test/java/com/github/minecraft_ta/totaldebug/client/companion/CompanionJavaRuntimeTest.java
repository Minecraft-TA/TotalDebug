package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionJavaRuntimeTest {
    private static final Set<String> REQUIRED_MODULES = Set.of(
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
            "jdk.jdi",
            "jdk.unsupported",
            "jdk.zipfs"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesTheExactCurrentJavaProcess() throws Exception {
        Path expected = Path.of(ProcessHandle.current().info().command().orElseThrow());

        assertEquals(expected.toAbsolutePath().normalize(), CompanionJavaRuntime.resolveCurrentExecutable());
    }

    @Test
    void acceptsACompleteJava21RuntimeContract() throws Exception {
        Path executable = Files.writeString(this.temporaryDirectory.resolve("java.exe"), "java");

        assertDoesNotThrow(() -> CompanionJavaRuntime.validate(executable, 21, REQUIRED_MODULES));
    }

    @Test
    void rejectsAnOlderRuntime() throws Exception {
        Path executable = Files.writeString(this.temporaryDirectory.resolve("java.exe"), "java");

        IOException exception = assertThrows(
                IOException.class,
                () -> CompanionJavaRuntime.validate(executable, 17, REQUIRED_MODULES)
        );
        assertEquals(
                "TotalDebugCompanion requires Java 21 or newer, but Minecraft is running Java 17",
                exception.getMessage()
        );
    }

    @Test
    void reportsEveryMissingRequiredModule() throws Exception {
        Path executable = Files.writeString(this.temporaryDirectory.resolve("java.exe"), "java");

        IOException exception = assertThrows(
                IOException.class,
                () -> CompanionJavaRuntime.validate(executable, 21, Set.of("java.base"))
        );
        String message = exception.getMessage();
        for (String module : REQUIRED_MODULES) {
            if (!module.equals("java.base")) {
                assertTrue(message.contains(module));
            }
        }
    }
}
