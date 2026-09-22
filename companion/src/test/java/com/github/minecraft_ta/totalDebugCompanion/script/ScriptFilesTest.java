package com.github.minecraft_ta.totalDebugCompanion.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ScriptFilesTest {
    @TempDir Path directory;
    @Test void listsOnlyProjectScriptReferencesAndReportsAnInvalidRoot() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        assertEquals(List.of(), files.listScripts());
        files.create(Files.createDirectories(files.root().resolve("nested")), "Debug", false, "return 1;");
        files.create(files.root(), "Root", false, "return 2;");
        Files.writeString(files.root().resolve("notes.txt"), "ignore");
        assertEquals(List.of("nested/Debug.tdscript", "Root.tdscript"), files.listScripts());
        Path invalid = Files.writeString(directory.resolve("not-a-directory"), "");
        assertThrows(IOException.class, () -> new ScriptFiles(invalid).listScripts());
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void scriptListingDoesNotFollowExternalOrCyclicJunctions() throws Exception {
        Path root = Files.createDirectory(directory.resolve("scripts"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("External.tdscript"), "return 1;");
        Files.writeString(root.resolve("Local.tdscript"), "return 2;");
        for (String name : List.of("external", "cycle")) {
            Path target = name.equals("external") ? outside : root;
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", root.resolve(name).toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
        }
        try { assertEquals(List.of("Local.tdscript"), new ScriptFiles(root).listScripts()); }
        finally { Files.delete(root.resolve("external")); Files.delete(root.resolve("cycle")); }
    }

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
    @Test void sameSizeExternalEditWithPreservedTimestampCannotBeOverwritten() throws Exception {
        var files = new ScriptFiles(directory.resolve("scripts"));
        Path path = files.create(files.root(), "Test", false, "return 1;");
        var loaded = files.read(path);
        Files.writeString(path, "return 2;");
        Files.setLastModifiedTime(path, loaded.version().modified());
        var edited = files.read(path);
        assertEquals(loaded.version().key(), edited.version().key());
        assertEquals(loaded.version().modified(), edited.version().modified());
        assertEquals(loaded.version().size(), edited.version().size());
        assertThrows(IOException.class, () -> files.save(path, "return 3;", loaded.version()));
        assertEquals("return 2;", Files.readString(path));
        var saved = files.save(path, "return 3;", edited.version());
        assertEquals(saved, files.save(path, "return 3;", saved));
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
