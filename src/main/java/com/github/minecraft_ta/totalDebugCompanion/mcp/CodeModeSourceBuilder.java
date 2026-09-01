package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;

import java.util.List;
import java.util.Objects;

final class CodeModeSourceBuilder {
    static final int MAX_SOURCE_BYTES = JavaSnippetSource.MAX_SOURCE_BYTES;

    private CodeModeSourceBuilder() {
    }

    static GeneratedSource build(
            int scriptId,
            String code,
            List<String> imports
    ) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(imports, "imports");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }

        String className = "McpCodeJob" + Math.abs((long) scriptId);
        StringBuilder snippet = new StringBuilder();
        for (String importName : imports) {
            String normalized = Objects.requireNonNull(importName, "imports must not contain null")
                    .trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("imports must not contain blank values");
            }
            snippet.append("import ").append(normalized).append(";\n");
        }
        snippet.append(code);
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body(className, snippet.toString());
        generated.requireExecutableSize();
        return new GeneratedSource(className, generated.source(), generated.sourceBytes());
    }

    record GeneratedSource(String className, String source, int sourceBytes) {
    }
}
