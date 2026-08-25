package com.github.minecraft_ta.totalDebugCompanion.model;

import com.formdev.flatlaf.util.StringUtils;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.CodeViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class CodeView implements IEditorPanel {

    private final Path path;
    private final EditorLocation location;
    private final CodeViewPanel codeViewPanel;
    private volatile CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);

    public CodeView(Path path, int offset) {
        this(path, offset, EditorLocation.forFile(path, CompanionApp.getWorkspaceDirectory()));
    }

    public CodeView(Path path, int offset, EditorLocation location) {
        this.path = path;
        this.location = location;
        this.codeViewPanel = new CodeViewPanel(this);
        reload(offset);
    }

    public CompletableFuture<Void> reload(int offset) {
        CompletableFuture<Void> task = CompletableFuture
                .supplyAsync(() -> readCode(this.path))
                .thenAcceptAsync(code -> {
                    this.codeViewPanel.setCode(code);
                    this.codeViewPanel.centerViewportOnOffset(offset);
                }, SwingUtilities::invokeLater);
        this.ready = task;
        task.exceptionally(failure -> {
            failure.printStackTrace();
            return null;
        });
        return task;
    }

    @Override
    public CompletableFuture<Void> ready() {
        return this.ready;
    }

    /**
     * @param offset the offset to scroll to
     */
    public void centerViewportOnOffset(int offset) {
        if (offset < 0)
            throw new IllegalArgumentException();

        this.codeViewPanel.centerViewportOnOffset(offset);
    }

    @Override
    public String getTitle() {
        String fullClassName = this.path.getFileName().toString().replace(".java", "");
        return fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
    }

    @Override
    public Icon getIcon() {
        return Icons.JAVA_CLASS;
    }

    @Override
    public String getTooltip() {
        return this.path.getFileName().toString();
    }

    @Override
    public Component getComponent() {
        return this.codeViewPanel;
    }

    @Override
    public EditorLocation getLocation() {
        return this.location;
    }

    @Override
    public BottomInformationBar getInformationBar() {
        return this.codeViewPanel.getBottomInformationBar();
    }

    @Override
    public void dispose() {
        this.codeViewPanel.dispose();
    }

    public Path getPath() {
        return this.path;
    }

    public static String readCode(Path path) {
        try {
            String code = Files.readString(path);
            code = code.replace("\r\n", "\n");
            code = StringUtils.removeTrailing(code, "\n");
            return code;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
