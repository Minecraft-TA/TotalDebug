package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaSourceMap;

import javax.lang.model.SourceVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the hidden Java class used by expression, durable-script, and MCP snippets. */
public final class JavaSnippetSource {
    public static final String PROGRAM_TYPE =
            "com.github.minecraft_ta.totaldebug.script.ScriptProgram";
    private static final String PROGRAM_SIMPLE_NAME = "ScriptProgram";
    public static final int MAX_SOURCE_BYTES = 30_000;

    private static final Pattern DIAGNOSTIC_LINE = Pattern.compile("(?i)\\bline (\\d+)");

    private JavaSnippetSource() {
    }

    public static GeneratedSource body(String className, String editorText) {
        return build(className, editorText, Mode.BODY);
    }

    public static GeneratedSource expression(String className, String expression) {
        return build(className, expression, Mode.EXPRESSION);
    }

    public static GeneratedSource build(String className, String editorText, Mode mode) {
        requireClassName(className);
        Objects.requireNonNull(editorText, "editorText");
        Objects.requireNonNull(mode, "mode");
        List<ImportSegment> imports = imports(editorText);
        String maskedBody = maskImports(editorText, imports);
        StringBuilder generated = new StringBuilder(editorText.length() + 320);
        List<MappingSegment> mappings = new ArrayList<>();
        for (ImportSegment segment : imports) {
            int generatedStart = generated.length();
            generated.append(editorText, segment.editorStart(), segment.editorEnd());
            mappings.add(new MappingSegment(
                    segment.editorStart(),
                    generatedStart,
                    segment.editorEnd() - segment.editorStart()
            ));
        }
        if (!generated.isEmpty() && generated.charAt(generated.length() - 1) != '\n') {
            generated.append('\n');
        }
        int importInsertionStart = generated.length();
        generated.append("import ").append(PROGRAM_TYPE).append(";\n");
        int importInsertionEnd = generated.length() - 1;
        generated.append("public final class ").append(className)
                .append(" extends ").append(PROGRAM_SIMPLE_NAME).append(" {\n")
                .append("    @Override\n")
                .append("    public Object run() throws Throwable {\n");

        int bodyStart;
        if (mode == Mode.EXPRESSION) {
            generated.append("        return (\n");
            bodyStart = generated.length();
            generated.append(maskedBody);
            generated.append("\n        );\n");
        } else {
            generated.append("        if (Boolean.TRUE.booleanValue()) {\n");
            bodyStart = generated.length();
            generated.append(maskedBody);
            generated.append("\n        }\n")
                    .append("        return noResult();\n");
        }
        mappings.add(new MappingSegment(0, bodyStart, editorText.length()));
        generated.append("    }\n}");

        int bytes = generated.toString().getBytes(StandardCharsets.UTF_8).length;
        SourceMap sourceMap = new SourceMap(
                editorText.length(),
                generated.length(),
                importInsertionStart,
                importInsertionEnd,
                imports.isEmpty() ? 0 : imports.getLast().editorEnd(),
                List.copyOf(mappings)
        );
        return new GeneratedSource(className, editorText, generated.toString(), bytes, mode, sourceMap);
    }

    private static List<ImportSegment> imports(String editorText) {
        List<ImportSegment> result = new ArrayList<>();
        int offset = 0;
        while (offset < editorText.length()) {
            offset = skipTrivia(editorText, offset);
            if (!keywordAt(editorText, offset, "import")) {
                break;
            }
            int end = importEnd(editorText, offset + "import".length());
            if (end < 0) {
                break;
            }
            int segmentEnd = includeLineTerminator(editorText, end);
            result.add(new ImportSegment(offset, segmentEnd));
            offset = segmentEnd;
        }
        return result;
    }

    private static int skipTrivia(String text, int offset) {
        int current = offset;
        while (current < text.length()) {
            char character = text.charAt(current);
            if (Character.isWhitespace(character)) {
                current++;
                continue;
            }
            if (character != '/' || current + 1 >= text.length()) {
                break;
            }
            char next = text.charAt(current + 1);
            if (next == '/') {
                int lineEnd = text.indexOf('\n', current + 2);
                current = lineEnd < 0 ? text.length() : lineEnd + 1;
                continue;
            }
            if (next == '*') {
                int commentEnd = text.indexOf("*/", current + 2);
                current = commentEnd < 0 ? text.length() : commentEnd + 2;
                continue;
            }
            break;
        }
        return current;
    }

    private static boolean keywordAt(String text, int offset, String keyword) {
        if (offset < 0 || !text.startsWith(keyword, offset)) {
            return false;
        }
        int end = offset + keyword.length();
        return (offset == 0 || !Character.isJavaIdentifierPart(text.charAt(offset - 1)))
                && (end == text.length() || !Character.isJavaIdentifierPart(text.charAt(end)));
    }

    private static int importEnd(String text, int offset) {
        int current = offset;
        boolean sawName = false;
        while (current < text.length()) {
            char character = text.charAt(current);
            if (character == ';') {
                return sawName ? current + 1 : -1;
            }
            if (character == '/' && current + 1 < text.length()) {
                char next = text.charAt(current + 1);
                if (next == '/') {
                    return -1;
                }
                if (next == '*') {
                    int commentEnd = text.indexOf("*/", current + 2);
                    if (commentEnd < 0) {
                        return -1;
                    }
                    current = commentEnd + 2;
                    continue;
                }
            }
            if (!Character.isWhitespace(character)) {
                sawName = true;
            }
            current++;
        }
        return -1;
    }

    private static int includeLineTerminator(String text, int offset) {
        int current = offset;
        while (current < text.length() && (text.charAt(current) == ' ' || text.charAt(current) == '\t')) {
            current++;
        }
        if (current < text.length() && text.charAt(current) == '\r') {
            current++;
        }
        return current < text.length() && text.charAt(current) == '\n' ? current + 1 : offset;
    }

    private static String maskImports(String editorText, List<ImportSegment> imports) {
        if (imports.isEmpty()) {
            return editorText;
        }
        char[] masked = editorText.toCharArray();
        for (ImportSegment segment : imports) {
            for (int index = segment.editorStart(); index < segment.editorEnd(); index++) {
                if (masked[index] != '\r' && masked[index] != '\n') {
                    masked[index] = ' ';
                }
            }
        }
        return new String(masked);
    }

    public static boolean isValidClassName(String className) {
        return className != null
                && SourceVersion.isIdentifier(className)
                && !SourceVersion.isKeyword(className, SourceVersion.RELEASE_21);
    }

    private static void requireClassName(String className) {
        Objects.requireNonNull(className, "className");
        if (!isValidClassName(className)) {
            throw new IllegalArgumentException("Invalid generated class name: " + className);
        }
    }

    public enum Mode {
        EXPRESSION,
        BODY
    }

    public record GeneratedSource(
            String className,
            String editorText,
            String source,
            int sourceBytes,
            Mode mode,
            SourceMap sourceMap
    ) {
        public void requireExecutableSize() {
            if (this.sourceBytes > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException(
                        "Generated source exceeds " + MAX_SOURCE_BYTES
                                + " UTF-8 bytes: " + this.sourceBytes
                );
            }
        }

        public JavaEditorSource editorSource() {
            return new JavaEditorSource(this.source, this.sourceMap, true);
        }

        public String mapDiagnostics(String diagnostics) {
            return mapDiagnostics(diagnostics, 0);
        }

        public String mapDiagnostics(String diagnostics, int editorLineOffset) {
            if (diagnostics == null || diagnostics.isEmpty()) {
                return diagnostics;
            }
            if (editorLineOffset < 0) {
                throw new IllegalArgumentException("editorLineOffset must not be negative");
            }
            String mapped = mapLinePattern(diagnostics, DIAGNOSTIC_LINE, "line ", editorLineOffset);
            Pattern stackFrame = Pattern.compile(Pattern.quote(this.className + ".java:") + "(\\d+)");
            return mapLinePattern(mapped, stackFrame, this.className + ".java:", editorLineOffset);
        }

        private String mapLinePattern(String text, Pattern pattern, String prefix, int editorLineOffset) {
            Matcher matcher = pattern.matcher(text);
            StringBuilder mapped = new StringBuilder(text.length());
            while (matcher.find()) {
                int generatedLine;
                try {
                    generatedLine = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException exception) {
                    continue;
                }
                int editorLine = editorLine(generatedLine) - editorLineOffset;
                if (editorLine < 1) {
                    continue;
                }
                matcher.appendReplacement(mapped, Matcher.quoteReplacement(prefix + editorLine));
            }
            matcher.appendTail(mapped);
            return mapped.toString();
        }

        private int editorLine(int generatedLine) {
            if (generatedLine < 1) {
                return -1;
            }
            int generatedOffset = lineStart(this.source, generatedLine);
            if (generatedOffset < 0) {
                return -1;
            }
            int editorOffset = this.sourceMap.toEditorOffset(generatedOffset);
            if (editorOffset < 0) {
                return -1;
            }
            int line = 1;
            for (int index = 0; index < Math.min(editorOffset, this.editorText.length()); index++) {
                if (this.editorText.charAt(index) == '\n') {
                    line++;
                }
            }
            return line;
        }

        private static int lineStart(String text, int requestedLine) {
            if (requestedLine == 1) {
                return 0;
            }
            int line = 1;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == '\n' && ++line == requestedLine) {
                    return index + 1;
                }
            }
            return -1;
        }
    }

    private record ImportSegment(int editorStart, int editorEnd) {
    }

    private record MappingSegment(int editorStart, int generatedStart, int length) {
        boolean containsEditor(int offset) {
            return offset >= this.editorStart && offset < this.editorStart + this.length;
        }

        boolean containsGenerated(int offset) {
            return offset >= this.generatedStart && offset < this.generatedStart + this.length;
        }
    }

    /** Offset mapping for copied import segments and the complete masked snippet body. */
    public static final class SourceMap implements JavaSourceMap {
        private final int editorLength;
        private final int generatedLength;
        private final int generatedImportInsertionStart;
        private final int generatedImportInsertionEnd;
        private final int editorImportInsertionOffset;
        private final List<MappingSegment> segments;

        private SourceMap(
                int editorLength,
                int generatedLength,
                int generatedImportInsertionStart,
                int generatedImportInsertionEnd,
                int editorImportInsertionOffset,
                List<MappingSegment> segments
        ) {
            this.editorLength = editorLength;
            this.generatedLength = generatedLength;
            this.generatedImportInsertionStart = generatedImportInsertionStart;
            this.generatedImportInsertionEnd = generatedImportInsertionEnd;
            this.editorImportInsertionOffset = editorImportInsertionOffset;
            this.segments = segments;
        }

        /** First editor offset after the import prefix; statement formatting starts here. */
        public int editorBodyOffset() {
            return this.editorImportInsertionOffset;
        }

        @Override
        public int toGeneratedOffset(int editorOffset) {
            if (editorOffset < 0 || editorOffset > this.editorLength) {
                return -1;
            }
            for (int index = 0; index < this.segments.size() - 1; index++) {
                MappingSegment segment = this.segments.get(index);
                if (segment.containsEditor(editorOffset)) {
                    return segment.generatedStart() + editorOffset - segment.editorStart();
                }
            }
            MappingSegment body = this.segments.getLast();
            return body.generatedStart() + editorOffset;
        }

        @Override
        public int toEditorOffset(int generatedOffset) {
            if (generatedOffset < 0 || generatedOffset > this.generatedLength) {
                return -1;
            }
            if (generatedOffset == this.generatedImportInsertionStart
                    || generatedOffset == this.generatedImportInsertionEnd) {
                return this.editorImportInsertionOffset;
            }
            for (MappingSegment segment : this.segments) {
                if (segment.containsGenerated(generatedOffset)) {
                    return segment.editorStart() + generatedOffset - segment.generatedStart();
                }
            }
            MappingSegment body = this.segments.getLast();
            if (generatedOffset == body.generatedStart() + body.length()) {
                return this.editorLength;
            }
            return -1;
        }

        public String mapInsertionText(int generatedOffset, String generatedText) {
            Objects.requireNonNull(generatedText, "generatedText");
            if (generatedOffset != this.generatedImportInsertionStart
                    && generatedOffset != this.generatedImportInsertionEnd) {
                return generatedText;
            }
            String importText = generatedText.strip();
            if (!importText.startsWith("import ") || !importText.endsWith(";")) {
                return generatedText;
            }
            return importText + '\n';
        }
    }
}
