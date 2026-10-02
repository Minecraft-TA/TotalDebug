package com.github.minecraft_ta.totalDebugCompanion.model;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeBinding;
import com.github.minecraft_ta.totalDebugCompanion.ui.EditorContext;
import com.formdev.flatlaf.util.StringUtils;
import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationViewState;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.CodeViewPanel;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.Optional;

public class CodeView implements IEditorPanel {
    private final RuntimeBinding runtimeBinding;
    @Override public RuntimeBinding runtimeBinding() { return runtimeBinding; }


    private final Path path;
    private final EditorLocation location;
    private final DebugEngine.Source debugSource;
    private final NavigationTarget navigationTarget;
    private final CodeViewPanel codeViewPanel;

    /** A local source file, showing {@code code} as {@link #read} read it; it reads nothing itself. */
    public CodeView(EditorContext context, Path path, String code) {
        this.runtimeBinding = null;
        this.path = path;
        this.location = EditorLocation.forFile(path, context.project().profile().workspaceDirectory());
        this.debugSource = null;
        this.navigationTarget = new NavigationTarget.LocalFile(path);
        this.codeViewPanel = new CodeViewPanel(context, this);
        this.codeViewPanel.setCode(code);
    }

    /** A decompiled class of {@code runtimeBinding}, whose source the decompiler already holds. */
    public CodeView(EditorContext context, DecompiledSource source, EditorLocation location, RuntimeBinding runtimeBinding) {
        this.runtimeBinding = runtimeBinding;
        this.path = source.path();
        this.location = location;
        this.debugSource = source.debugSource();
        this.navigationTarget = new NavigationTarget.RuntimeClass(source.binaryName());
        this.codeViewPanel = new CodeViewPanel(context, this);
        this.codeViewPanel.setCode(source.contents());
    }

    /** Shows {@code code} read again from the local file, as before a navigation places an offset in it. */
    public void replaceCode(String code) {
        this.codeViewPanel.setCode(code);
    }

    /** Places the caret at {@code offset}, if {@code stillWanted} still holds then. */
    public CompletableFuture<Void> navigateToOffset(int offset, BooleanSupplier stillWanted) {
        if (offset < 0) throw new IllegalArgumentException("The offset must not be negative");
        return this.codeViewPanel.navigateToOffset(offset, stillWanted);
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

    /** Reads a local source file to open it, on file work. */
    public static String read(Path path) throws IOException {
        String code = Files.readString(path);
        code = code.replace("\r\n", "\n");
        return StringUtils.removeTrailing(code, "\n");
    }
}
