package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;

import java.nio.file.Path;
import java.util.Objects;

public record DecompiledSource(
        Path path,
        String binaryName,
        String contents,
        SourceLineMap lineMap,
        SourceVariableNames variableNames,
        RuntimeSnapshotBytecodeSource.ClassOrigin origin
) {
    public DecompiledSource {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (Objects.requireNonNull(binaryName, "binaryName").isBlank()) {
            throw new IllegalArgumentException("Decompiled source binary name is blank");
        }
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(lineMap, "lineMap");
        Objects.requireNonNull(variableNames, "variableNames");
    }

    public DebugEngine.Source debugSource() {
        return new DebugEngine.Source(
                this.path.toUri(),
                this.binaryName,
                this.contents,
                this.lineMap,
                this.variableNames
        );
    }
}
