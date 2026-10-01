package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ResourceViewPanel;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Document tabs built the way navigation builds them, from their open read, for tests that open them directly. */
public final class OpenedTabs {
    private OpenedTabs() {
    }

    /** What opening the script at {@code path} reads. */
    public static ScriptView.Read scriptRead(EditorContext context, Path path) {
        try {
            return ScriptView.read(context, path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A script tab of {@code path}, read as navigation reads it. */
    public static ScriptView script(EditorContext context, Path path) {
        return new ScriptView(context, scriptRead(context, path));
    }

    /** A resource tab of {@code source}, read as navigation reads it. */
    public static ResourceView resource(EditorContext context, ContentSource source, RuntimeBinding runtime) {
        try {
            return new ResourceView(context, source, runtime, ResourceView.read(context, source));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A resource tab of {@code source} showing {@code text}, for a source that need not exist on disk. */
    public static ResourceView text(EditorContext context, ContentSource source, RuntimeBinding runtime, String text) {
        LoadedResource.Text content = new LoadedResource.Text(text, SyntaxConstants.SYNTAX_STYLE_NONE,
                StandardCharsets.UTF_8.name(), text.getBytes(StandardCharsets.UTF_8).length);
        return new ResourceView(context, source, runtime, new ResourceViewPanel.Opened(content, null, null));
    }
}
