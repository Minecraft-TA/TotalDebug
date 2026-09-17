package com.github.minecraft_ta.totaldebug.evaluation;

import javax.tools.Diagnostic;
import java.util.Locale;

/** One compiler diagnostic; end is exclusive and -1 denotes an unavailable position. */
public record CompilationDiagnostic(Diagnostic.Kind kind, String code, String message,
                                    int start, int end, long line, long column) {
    static CompilationDiagnostic from(Diagnostic<?> diagnostic) {
        return new CompilationDiagnostic(diagnostic.getKind(), diagnostic.getCode(), diagnostic.getMessage(Locale.ROOT),
                (int) diagnostic.getStartPosition(), (int) diagnostic.getEndPosition(), diagnostic.getLineNumber(), diagnostic.getColumnNumber());
    }

    public String formatted() { return (line < 1 ? "" : "line " + line + ": ") + message; }

    public boolean syntaxError() {
        return code.startsWith("compiler.err.expected") || code.startsWith("compiler.err.illegal.start")
                || code.equals("compiler.err.premature.eof") || code.startsWith("compiler.err.unclosed");
    }
}
