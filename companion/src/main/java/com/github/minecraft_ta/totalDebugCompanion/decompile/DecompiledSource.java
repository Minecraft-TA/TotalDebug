package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.model.EditorLocation;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.github.minecraft_ta.totalDebugCompanion.ui.presentation.RuntimeModulePresentation;
import java.nio.file.Path;
import java.util.Objects;

public record DecompiledSource(Path path, SourceDocument document, RuntimeSnapshotBytecodeSource.ClassOrigin origin) {
    public DecompiledSource {
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        Objects.requireNonNull(document);
    }
    public String binaryName() { return document.binaryName(); }
    public String contents() { return document.contents(); }
    public SourceLineMap lineMap() { return document.lineMap(); }
    public SourceVariableNames variableNames() { return document.variableNames(); }
    public DebugEngine.Source debugSource() { return new DebugEngine.Source(path.toUri(), document); }
    public EditorLocation location() {
        return origin == null ? EditorLocation.forFile(path, null) : EditorLocation.forRuntimeClass(
                binaryName(), origin.logicalSource(), RuntimeModulePresentation.of(origin.module()).label(), origin.module().id());
    }
}
