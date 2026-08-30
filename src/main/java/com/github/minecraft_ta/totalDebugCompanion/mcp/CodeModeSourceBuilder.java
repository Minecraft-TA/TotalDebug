package com.github.minecraft_ta.totalDebugCompanion.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

final class CodeModeSourceBuilder {
    static final int MAX_SOURCE_BYTES = 30_000;

    private CodeModeSourceBuilder() {
    }

    static GeneratedSource build(
            int scriptId,
            String code,
            List<String> imports,
            UnaryOperator<String> baseScriptMerger
    ) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(imports, "imports");
        Objects.requireNonNull(baseScriptMerger, "baseScriptMerger");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }

        String className = "McpCodeJob" + Math.abs((long) scriptId);
        StringBuilder source = new StringBuilder();
        for (String importName : imports) {
            String normalized = Objects.requireNonNull(importName, "imports must not contain null")
                    .trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("imports must not contain blank values");
            }
            source.append("import ").append(normalized).append(";\n");
        }
        source.append("public final class ").append(className).append(" extends BaseScript {\n")
                .append("    private Object resultValue;\n")
                .append("    private boolean resultSet;\n")
                .append("    public void result(Object value) {\n")
                .append("        this.resultValue = value;\n")
                .append("        this.resultSet = true;\n")
                .append("    }\n")
                .append("    @Override\n")
                .append("    public void run() throws Throwable {\n")
                .append(code).append('\n')
                .append("    }\n")
                .append("}\n");

        String mergedSource = Objects.requireNonNull(
                baseScriptMerger.apply(source.toString()),
                "baseScriptMerger returned null"
        );
        int sourceBytes = mergedSource.getBytes(StandardCharsets.UTF_8).length;
        if (sourceBytes > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "Generated source exceeds " + MAX_SOURCE_BYTES + " UTF-8 bytes: " + sourceBytes
            );
        }
        return new GeneratedSource(className, mergedSource, sourceBytes);
    }

    record GeneratedSource(String className, String source, int sourceBytes) {
    }
}
