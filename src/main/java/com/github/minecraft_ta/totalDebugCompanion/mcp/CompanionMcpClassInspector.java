package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.decompile.CompanionDecompilationService;
import com.github.minecraft_ta.totalDebugCompanion.decompile.DecompiledSource;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class CompanionMcpClassInspector {
    private final RuntimeClassAccess runtimeClasses;

    CompanionMcpClassInspector(Supplier<CompanionDecompilationService> decompilationService) {
        this(binaryName -> Objects.requireNonNull(
                        decompilationService.get(),
                        "decompilationService returned null"
                )
                .load(binaryName)
                .join());
    }

    CompanionMcpClassInspector(RuntimeClassAccess runtimeClasses) {
        this.runtimeClasses = Objects.requireNonNull(runtimeClasses, "runtimeClasses");
    }

    Map<String, Object> source(String binaryName) {
        String checkedName = requireBinaryName(binaryName);
        DecompiledSource source = this.runtimeClasses.source(checkedName);
        if (source == null) {
            throw new IllegalArgumentException("Class not found: " + checkedName);
        }
        return Map.of("source", source.contents());
    }

    private static String requireBinaryName(String binaryName) {
        if (binaryName == null || binaryName.isBlank()
                || binaryName.indexOf('/') >= 0
                || binaryName.indexOf('\\') >= 0
                || binaryName.endsWith(".class")) {
            throw new IllegalArgumentException("binary_name must be a Java binary name");
        }
        return binaryName;
    }

    @FunctionalInterface
    interface RuntimeClassAccess {
        DecompiledSource source(String binaryName);
    }
}
