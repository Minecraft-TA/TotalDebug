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
import java.util.concurrent.CompletionException;

public class CodeView implements IEditorPanel {

    private final Path path;
    private final EditorLocation location;
    private final CodeViewPanel codeViewPanel;

    public CodeView(Path path, int offset) {
        this(path, offset, EditorLocation.forFile(path, CompanionApp.getWorkspaceDirectory()));
    }

    public CodeView(Path path, int offset, EditorLocation location) {
        this.path = path;
        this.location = location;
        this.codeViewPanel = new CodeViewPanel(this);
        reload(offset);
    }

    public void reload(int offset) {
        CompletableFuture.runAsync(() -> {
            try {
                var code = readCode(this.path);

                SwingUtilities.invokeLater(() -> {
                    codeViewPanel.setCode(code);
                    codeViewPanel.centerViewportOnOffset(offset);
                });
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).exceptionally(e -> {
            e.printStackTrace();
            return null;
        });
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
