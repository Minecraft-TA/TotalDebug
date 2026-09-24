package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaSourceMap;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaImports.Import;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Expression;

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

    /** Classifies syntax without executing code; editor expansion does not affect its meaning. */
    public static Mode detectMode(String source) {
        String body = splitImports(source).body().trim();
        var parser = JdtConfiguration.createParser();
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(body.toCharArray());
        var node = parser.createAST(null);
        if (!(node instanceof Expression)
                || !onlyTrivia(body.substring(0, node.getStartPosition()))
                || !onlyTrivia(body.substring(node.getStartPosition() + node.getLength()))) return Mode.BODY;
        boolean[] valid = {true};
        node.accept(new ASTVisitor() {
            @Override public void preVisit(ASTNode child) {
                if ((child.getFlags() & (ASTNode.MALFORMED
                        | ASTNode.RECOVERED)) != 0) valid[0] = false;
            }
        });
        return valid[0] ? Mode.EXPRESSION : Mode.BODY;
    }

    private static boolean onlyTrivia(String source) {
        var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        try {
            return scanner.getNextToken() == ITerminalSymbols.TokenNameEOF;
        } catch (InvalidInputException invalid) {
            return false;
        }
    }

    public static GeneratedSource build(String className, String editorText, Mode mode) {
        requireClassName(className);
        Objects.requireNonNull(editorText, "editorText");
        Objects.requireNonNull(mode, "mode");
        List<Import> imports = JavaImports.read(editorText);
        String maskedBody = maskImports(editorText, imports);
        StringBuilder generated = new StringBuilder(editorText.length() + 320);
        List<MappingSegment> mappings = new ArrayList<>();
        for (Import segment : imports) {
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

    public record FragmentParts(String imports, String body) { }

    public static FragmentParts splitImports(String text) {
        List<Import> segments = JavaImports.read(text);
        StringBuilder declarations = new StringBuilder();
        for (Import segment : segments) declarations.append(text, segment.editorStart(), segment.editorEnd()).append('\n');
        return new FragmentParts(declarations.toString(), maskImports(text, segments));
    }

    private static String maskImports(String editorText, List<Import> imports) {
        if (imports.isEmpty()) {
            return editorText;
        }
        char[] masked = editorText.toCharArray();
        for (Import segment : imports) {
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

        @Override
        public int toEditorDiagnosticOffset(int generatedOffset, boolean syntax) {
            // Completion's synthetic import-insertion positions are not locations in user code.
            for (MappingSegment segment : this.segments) {
                if (segment.containsGenerated(generatedOffset)) return segment.editorStart() + generatedOffset - segment.generatedStart();
            }
            MappingSegment body = this.segments.getLast();
            return syntax && generatedOffset >= body.generatedStart() + body.length()
                    && generatedOffset <= this.generatedLength ? this.editorLength : -1;
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
