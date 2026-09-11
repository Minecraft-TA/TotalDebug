package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProjectRegistryTest {
    @TempDir Path directory;

    @Test void preservesTheRememberedProjectAndKeepsInstanceFilesInPlace() throws Exception {
        AppPaths paths = new AppPaths(directory.resolve("app"));
        CompanionProfile a = profile("a");
        CompanionProfile b = profile("b");
        Path aScript = Files.writeString(a.dataDirectory().resolve("script.txt"), "A");
        Path bScript = Files.writeString(b.dataDirectory().resolve("script.txt"), "B");
        com.github.minecraft_ta.totaldebug.storage.JsonFiles.write(paths.profile(), a.toJson());
        var registry = ProjectRegistry.open(paths);
        assertEquals(a, registry.selected());
        assertFalse(Files.exists(paths.profile()));
        registry.select(b);
        registry.select(a);
        var reopened = ProjectRegistry.open(paths);
        assertEquals(a, reopened.selected());
        assertEquals(2, reopened.projects().size());
        assertEquals("A", Files.readString(aScript));
        assertEquals("B", Files.readString(bScript));
    }

    @Test void failedRegistryWriteDoesNotChangeTheSelectedProject() throws Exception {
        AppPaths paths = new AppPaths(directory.resolve("app"));
        var registry = ProjectRegistry.open(paths);
        var a = profile("a");
        registry.select(a);
        Files.delete(paths.projects());
        Files.createDirectory(paths.projects());
        Files.writeString(paths.projects().resolve("occupied"), "x");
        assertThrows(java.io.IOException.class, () -> registry.select(profile("b")));
        assertEquals(a, registry.selected());
        assertEquals(1, registry.projects().size());
    }

    private CompanionProfile profile(String name) throws Exception {
        Path game = Files.createDirectories(directory.resolve(name).resolve("minecraft"));
        return new CompanionProfile(name, Files.createDirectories(game.resolve("total-debug")), game);
    }
}
