package com.github.minecraft_ta.totalDebugCompanion.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ScriptFilesTest {
    @TempDir Path directory;
    @Test void nestedCreateRenameMoveAndSaveKeepTwoSameNamedScriptsIndependent() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path first = files.create(files.root(), "First folder", true, "");
        Path second = files.create(files.root(), "Second", true, "");
        Path one = files.create(first, "Test", false, "return 1;");
        Path two = files.create(second, "Test", false, "return 2;");
        var loaded = files.read(one);
        assertNotEquals(files.read(two).text(), loaded.text());
        Path renamed = files.move(first, directory.resolve("scripts/Renamed"));
        Path moved = renamed.resolve("Test.tdscript");
        files.save(moved, "return 3;", loaded.version());
        assertEquals("return 3;", Files.readString(moved));
        assertFalse(Files.exists(first));
        assertEquals("return 2;", Files.readString(two));
    }
    @Test void rejectsCollisionsRootMutationTraversalAndDescendantMoves() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path folder = files.create(files.root(), "Folder", true, "");
        Path original = files.create(folder, "Original", false, "keep");
        Path other = files.create(folder, "Other", false, "other");
        assertThrows(IOException.class, () -> files.move(original, other));
        assertThrows(IOException.class, () -> files.create(folder, "Original", false, "replace"));
        assertThrows(IOException.class, () -> files.move(folder, folder.resolve("Child")));
        assertThrows(IOException.class, () -> files.move(files.root(), directory.resolve("Gone")));
        assertThrows(IOException.class, () -> files.delete(files.root(), false));
        assertThrows(IOException.class, () -> files.move(original, directory.resolve("Outside.tdscript")));
        assertEquals("keep", Files.readString(original));
        assertEquals("other", Files.readString(other));
    }
    @Test void deletedOrReplacedFileCannotBeResurrectedByAnOldEditor() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path parent = files.create(files.root(), "Folder", true, "");
        Path path = files.create(parent, "Test", false, "old");
        var version = files.read(path).version();
        files.delete(parent, false);
        assertThrows(IOException.class, () -> files.save(path, "stale", version));
        assertFalse(Files.exists(parent));
        files.create(files.root(), "Folder", true, "");
        files.create(parent, "Test", false, "replacement");
        assertThrows(IOException.class, () -> files.save(path, "stale", version));
        assertEquals("replacement", Files.readString(path));
    }
    @Test void caseOnlyRenameAndFilesystemNames() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path path = files.create(files.root(), "MyScript", false, "");
        files.move(path, path.resolveSibling("Myscript.tdscript"));
        try(var children = Files.list(files.root())) { assertEquals("Myscript.tdscript", children.findFirst().orElseThrow().getFileName().toString()); }
        for (String bad : new String[]{"..", "a/b", "a\\b", "CON", "COM1", "NUL.txt", "trailing.", "trailing ", "a:b"})
            assertThrows(IOException.class, () -> ScriptFiles.validateName(bad, false), bad);
        Path folder = files.create(files.root(), "Folder.tdscript", true, "");
        assertTrue(Files.isDirectory(files.move(folder, folder.resolveSibling("Folder"))));
        ScriptFiles.validateName("My folder", false);
        assertThrows(IOException.class, () -> ScriptFiles.validateName("My folder", true));
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void permanentDeleteDoesNotFollowJunctionIntoSiblingFolder() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path selected = files.create(files.root(), "Selected", true, "");
        Path sibling = files.create(files.root(), "Sibling", true, "");
        Path kept = files.create(sibling, "Keep", false, "keep");
        Path junction = selected.resolve("Link");
        var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), sibling.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        try {
            assertThrows(IOException.class, () -> files.delete(selected, false));
            assertThrows(IOException.class, () -> files.move(junction.resolve("Keep.tdscript"), selected.resolve("Moved.tdscript")));
            assertEquals("keep", Files.readString(kept));
        } finally { Files.delete(junction); }
    }
}
