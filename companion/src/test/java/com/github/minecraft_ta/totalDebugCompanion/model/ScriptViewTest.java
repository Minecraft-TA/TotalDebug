package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptViewTest {
    @TempDir Path directory;

    @Test void basenameRenameChangesLocationWithoutChangingTheOpenEditorsGeneratedIdentity() throws Exception {
        var profile = CompanionProfile.forGame(Files.createDirectory(directory.resolve("game")));
        var project = new ProjectScope(new Object(), profile, InstanceState.inMemory());
        try {
            Path root = Files.createDirectories(project.scriptFiles().root());
            Path original = Files.writeString(root.resolve("Original.tdscript"), "return 42;");
            var context = new EditorContext(null, null, null, project, null, null, null, null, null, null, null, null);
            var view = new ScriptView(context, original);
            String editorKey = view.editorKey();
            String generated = JavaSnippetSource.body(view.compilationName(), view.getSourceText()).source();
            Path moved = Files.move(original, Files.createDirectory(root.resolve("nested")).resolve("Renamed.tdscript"));
            view.relocated(original, moved);

            assertEquals("Renamed.tdscript", view.getTitle());
            assertEquals("Renamed", view.getScriptName());
            assertEquals(new NavigationTarget.LocalFile(moved), view.getNavigationTarget());
            assertEquals(root.relativize(moved).toString(), view.getTooltip());
            assertEquals(editorKey, view.editorKey());
            assertEquals(generated, JavaSnippetSource.body(view.compilationName(), view.getSourceText()).source());
        } finally { project.retire(); project.close(); }
    }
}
