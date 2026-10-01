package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.StreamSupport;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;

public class ScriptView implements IEditorPanel {
    public static final String FILE_EXTENSION = ".tdscript";

    private final EditorContext context;
    private final String text;
    private Path path;
    private ScriptFiles.Snapshot savedSnapshot;
    private final String compilationName;
    private final String editorKey = "script-editor-" + UUID.randomUUID();
    private boolean fileOperation;
    private boolean deleted;

    public String compilationName() { return compilationName; }
    public String editorKey() { return editorKey; }
    public boolean fileOperation() { return fileOperation; }
    public void setFileOperation(boolean value) {
        fileOperation = value;
        if (scriptPanel != null) scriptPanel.setFileOperation(value);
    }
    public String currentText() { return scriptPanel == null ? text : scriptPanel.sourceText(); }
    public CompletableFuture<Void> pendingSave() { return scriptPanel == null ? CompletableFuture.completedFuture(null) : scriptPanel.pendingSave(); }
    public boolean isRunning() { return scriptPanel != null && scriptPanel.isRunning(); }
    public void persist(String contents) throws IOException {
        savedSnapshot = context.project().scriptFiles().save(path, contents, savedSnapshot);
    }
    public void relocated(Path from, Path to) { path = ScriptFiles.relocated(path, from, to); }
    public void deleted() { deleted = true; }
    public void discardOnClose() { deleted = true; }
    public void edited() { deleted = false; }
    public void saved(String contents) { if (scriptPanel != null) scriptPanel.saved(contents); }

    protected ScriptPanel scriptPanel;

    /** What opening a script reads: where it lies and what it holds. */
    public record Read(Path path, ScriptFiles.Snapshot snapshot) {
    }

    /** Reads the script at {@code path} to open it, on file work, through the project's script files. */
    public static Read read(EditorContext context, Path path) throws IOException {
        if (context.project() == null) throw new IllegalStateException("Open a project before opening scripts");
        Path resolved = context.project().scriptFiles().resolve(path);
        String name = scriptName(resolved);
        if (!JavaSnippetSource.isValidClassName(name)) throw new IOException("Invalid script name: " + name);
        return new Read(resolved, context.project().scriptFiles().read(resolved));
    }

    /** A tab showing what {@link #read} read; it reads nothing itself. */
    public ScriptView(EditorContext context, Read read) {
        this.context = context;
        this.path = read.path();
        this.compilationName = scriptName(read.path());
        this.text = read.snapshot().text();
        this.savedSnapshot = read.snapshot();
    }

    @Override
    public boolean canClose() {
        return !fileOperation && (deleted || this.scriptPanel == null || this.scriptPanel.canSave());
    }

    public String getSourceText() {
        return text;
    }

    public Path getPath() {
        return path;
    }

    /** Places the caret at {@code offset} in the text on screen, if {@code stillWanted} still holds then. */
    public CompletableFuture<Void> navigateToOffset(int offset, BooleanSupplier stillWanted) {
        return ((ScriptPanel) getComponent()).navigateToOffset(offset, stillWanted);
    }

    @Override
    public String getTitle() {
        return this.path.getFileName().toString();
    }

    public String getScriptName() {
        return scriptName(this.path);
    }

    private static String scriptName(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
    }

    @Override
    public String getTooltip() {
        return context.project().scriptFiles().root().relativize(path).toString();
    }

    @Override
    public Icon getIcon() {
        return Icons.SCRIPT_FILE;
    }

    @Override
    public Component getComponent() {
        if (this.scriptPanel == null)
            this.scriptPanel = new ScriptPanel(context, this);
        return this.scriptPanel;
    }

    @Override
    public EditorLocation getLocation() {
        return new EditorLocation("Scripts", StreamSupport.stream(context.project().scriptFiles().root().relativize(path).spliterator(), false).map(Path::toString).toList(), this.path.toString());
    }


    @Override
    public NavigationTarget getNavigationTarget() {
        return new NavigationTarget.LocalFile(this.path);
    }

    @Override
    public JavaEditorContext getJavaEditorContext() {
        return (ScriptPanel) getComponent();
    }

    @Override
    public NavigationViewState captureNavigationViewState() {
        return this.scriptPanel == null
                ? NavigationViewState.EMPTY
                : this.scriptPanel.captureNavigationViewState();
    }

    @Override
    public void restoreNavigationViewState(NavigationViewState state) {
        ((ScriptPanel) getComponent()).restoreNavigationViewState(state);
    }

    @Override
    public void dispose() {
        if (this.scriptPanel != null) {
            this.scriptPanel.dispose();
        }
    }
}
