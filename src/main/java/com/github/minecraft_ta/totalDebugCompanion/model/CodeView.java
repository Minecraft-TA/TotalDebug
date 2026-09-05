package com.github.minecraft_ta.totalDebugCompanion.model;

import com.formdev.flatlaf.util.StringUtils;
import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.CodeViewPanel;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

public class CodeView implements IEditorPanel {

    private final Path path;
    private final EditorLocation location;
    private final DebugEngine.Source debugSource;
    private final NavigationTarget navigationTarget;
    private final CodeViewPanel codeViewPanel;
    private volatile CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);

    public CodeView(Path path, int offset) {
        this(path, offset, EditorLocation.forFile(path, CompanionApp.getWorkspaceDirectory()));
    }

    public CodeView(Path path, int offset, EditorLocation location) {
        this.path = path;
        this.location = location;
        this.debugSource = null;
        this.navigationTarget = new NavigationTarget.LocalFile(path);
        this.codeViewPanel = new CodeViewPanel(this);
        reload(offset);
    }

    public CodeView(DecompiledSource source, int offset, EditorLocation location) {
        this.path = source.path();
        this.location = location;
        this.debugSource = source.debugSource();
        this.navigationTarget = new NavigationTarget.RuntimeClass(source.binaryName());
        this.codeViewPanel = new CodeViewPanel(this);
        setCode(source.contents(), offset);
    }

    public void reload(int offset) {
        CompletableFuture<Void> task = CompletableFuture
                .supplyAsync(() -> readCode(this.path))
                .thenAcceptAsync(code -> {
                    this.codeViewPanel.setCode(code);
                    this.codeViewPanel.navigateToOffset(offset);
                }, SwingUtilities::invokeLater);
        this.ready = task;
        task.exceptionally(failure -> {
            failure.printStackTrace();
            return null;
        });
    }

    private void setCode(String code, int offset) {
        this.ready = CompletableFuture.runAsync(
                () -> {
                    this.codeViewPanel.setCode(code);
                    this.codeViewPanel.navigateToOffset(offset);
                },
                SwingUtilities::invokeLater
        );
    }

    @Override
    public CompletableFuture<Void> ready() {
        return this.ready;
    }

    /**
     * @param offset the offset to scroll to
     */
    public void navigateToOffset(int offset) {
        if (offset < 0)
            throw new IllegalArgumentException();

        this.codeViewPanel.navigateToOffset(offset);
    }

    public void showExecutionLine(int displayedLine) {
        this.codeViewPanel.showExecutionLine(displayedLine);
    }

    @Override
    public String getTitle() {
        String fullClassName = this.navigationTarget instanceof NavigationTarget.RuntimeClass(String binaryName)
                ? binaryName : this.path.getFileName().toString().replace(".java", "");
        return fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
    }

    @Override
    public Icon getIcon() {
        return Icons.JAVA_CLASS;
    }

    @Override
    public String getTooltip() {
        return this.navigationTarget instanceof NavigationTarget.RuntimeClass(String binaryName)
                ? binaryName + ".java" : this.path.getFileName().toString();
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
    public NavigationTarget getNavigationTarget() {
        return this.navigationTarget;
    }

    @Override
    public JavaEditorContext getJavaEditorContext() {
        return this.codeViewPanel;
    }

    @Override
    public NavigationViewState captureNavigationViewState() {
        return this.codeViewPanel.captureNavigationViewState();
    }

    @Override
    public void restoreNavigationViewState(NavigationViewState state) {
        this.codeViewPanel.restoreNavigationViewState(state);
    }

    @Override
    public void dispose() {
        this.codeViewPanel.dispose();
    }

    public Path getPath() {
        return this.path;
    }

    public Optional<DebugEngine.Source> getDebugSource() {
        return Optional.ofNullable(this.debugSource);
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
