package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectDirectoriesTest {
    @TempDir Path root;

    @Test void resolvesInstanceAndItsChildrenToOneProfileWithoutWrites() throws Exception {
        Path instance = Files.createDirectories(root.resolve("Pack"));
        Path game = Files.createDirectory(instance.resolve("minecraft"));
        Path mods = Files.createDirectory(game.resolve("mods"));
        var expected = CompanionProfile.forGame(game);
        assertEquals(expected, ProjectDirectories.resolve(instance));
        assertEquals(expected, ProjectDirectories.resolve(game));
        assertEquals(expected, ProjectDirectories.resolve(mods));
        assertFalse(Files.exists(expected.dataDirectory()));
        Path data = Files.createDirectory(game.resolve("total-debug"));
        assertEquals(expected, ProjectDirectories.resolve(data));
    }

    @Test void rejectsUnrelatedAndEmptyNamedDirectories() throws Exception {
        Path unrelated = Files.createDirectory(root.resolve("unrelated"));
        Path named = Files.createDirectory(unrelated.resolve("total-debug"));
        assertThrows(IllegalArgumentException.class, () -> ProjectDirectories.resolve(unrelated));
        assertThrows(IllegalArgumentException.class, () -> ProjectDirectories.resolve(named));
        try (var children = Files.list(named)) { assertEquals(0, children.count()); }
        Files.createDirectories(named.resolve("cache/runtime"));
        assertThrows(IllegalArgumentException.class, () -> ProjectDirectories.resolve(named));
    }

    @Test void recognizesSavedScriptsWithoutAModsDirectory() throws Exception {
        Path scripts = Files.createDirectories(root.resolve("game/total-debug/scripts"));
        assertEquals(root.resolve("game"), ProjectDirectories.resolve(scripts.getParent()).workspaceDirectory());
    }

    @Test void prismInstanceNamesDoNotOverrideTheirGameChildren() throws Exception {
        for (String name : List.of("mods", "total-debug")) {
            for (String child : List.of("minecraft", ".minecraft")) {
                Path instance = Files.createDirectories(root.resolve(child).resolve(name));
                Path game = Files.createDirectory(instance.resolve(child));
                Files.createDirectory(game.resolve("mods"));
                assertEquals(CompanionProfile.forGame(game), ProjectDirectories.resolve(instance));
            }
        }
    }

    @Test void anOrphanedGeneratedIndexDoesNotMakeAProject() throws Exception {
        Path game = Files.createDirectory(root.resolve("orphan"));
        Path data = Files.createDirectories(game.resolve("total-debug/cache/runtime")).getParent().getParent();
        Path index = Files.writeString(data.resolve("cache/runtime/index.jindex"), "orphaned generated index");
        assertThrows(IllegalArgumentException.class, () -> ProjectDirectories.resolve(game));
        assertThrows(IllegalArgumentException.class, () -> ProjectDirectories.resolve(data));
        assertEquals("orphaned generated index", Files.readString(index));
    }
}
