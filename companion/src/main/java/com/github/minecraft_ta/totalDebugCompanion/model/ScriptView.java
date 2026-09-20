package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.ScriptPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.StreamSupport;
import java.util.concurrent.CompletableFuture;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;

public class ScriptView implements IEditorPanel {
    public static final String FILE_EXTENSION = ".tdscript";

    private final EditorContext context;
    private final String text;
    private Path path;
    private ScriptFiles.Version version;
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
        version = context.project().scriptFiles().save(path, contents, version);
    }
    public void relocated(Path from, Path to) { path = ScriptFiles.relocated(path, from, to); }
    public void deleted() { deleted = true; }
    public void discardOnClose() { deleted = true; }
    public void edited() { deleted = false; }
    public void saved(String contents) { if (scriptPanel != null) scriptPanel.saved(contents); }

    protected ScriptPanel scriptPanel;

    public ScriptView(EditorContext context, Path path) {
        this.context = context;
        if (context.project() == null) throw new IllegalStateException("Open a project before opening scripts");
        try {
            this.path = context.project().scriptFiles().resolve(path);
            this.compilationName = getScriptName();
            if (!JavaSnippetSource.isValidClassName(compilationName)) throw new IOException("Invalid script name: " + compilationName);
            var loaded = context.project().scriptFiles().read(this.path);
            this.text = loaded.text();
            this.version = loaded.version();
        } catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
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

    public void navigateToOffset(int offset) {
        ((ScriptPanel) getComponent()).navigateToOffset(offset);
    }

    @Override
    public String getTitle() {
        return this.path.getFileName().toString();
    }

    public String getScriptName() {
        String fileName = this.path.getFileName().toString();
        return fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
    }

    @Override
    public String getTooltip() {
        return context.project().scriptFiles().root().relativize(path).toString();
    }

    @Override
    public Icon getIcon() {
        return Icons.JAVA_FILE;
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
    public BottomInformationBar getInformationBar() {
        return this.scriptPanel == null ? null : this.scriptPanel.getBottomInformationBar();
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
