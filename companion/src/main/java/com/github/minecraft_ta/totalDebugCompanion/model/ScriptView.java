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
import java.nio.file.Files;
import java.nio.file.Path;

public class ScriptView implements IEditorPanel {
    public static final String FILE_EXTENSION = ".tdscript";

    private final EditorContext context;
    private final String text;
    private final Path path;
    protected ScriptPanel scriptPanel;

    public ScriptView(EditorContext context, String scriptName) {
        this.context = context;
        if (!JavaSnippetSource.isValidClassName(scriptName)) {
            throw new IllegalArgumentException("Invalid script name: " + scriptName);
        }
        if (context.project() == null) {
            throw new IllegalStateException("Open a Minecraft profile before creating scripts");
        }
        this.path = context.project().paths().scripts().resolve(scriptName + FILE_EXTENSION);
        try {
            Files.createDirectories(this.path.getParent());
            if (!Files.exists(this.path)) {
                this.text = "";
                com.github.minecraft_ta.totaldebug.storage.AtomicFiles.createNewString(this.path, this.text);
            } else {
                this.text = Files.readString(this.path);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean canClose() {
        return this.scriptPanel == null || this.scriptPanel.canSave();
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
        return null;
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
        return new EditorLocation("Scripts", java.util.List.of(this.path.getFileName().toString()), this.path.toString());
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
