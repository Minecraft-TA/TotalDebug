package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import org.eclipse.jdt.core.compiler.IProblem;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.parser.*;

public class CustomJavaParser extends AbstractParser {

    private final ASTCache cache;
    private final String astKey;

    public CustomJavaParser(ASTCache cache, String astKey) {
        this.cache = cache;
        this.astKey = astKey;
    }

    @Override
    public ParseResult parse(RSyntaxDocument doc, String style) {
        var result = new DefaultParseResult(this);
        result.setParsedLines(0, doc.getDefaultRootElement().getElementCount() - 1);

        if (!SyntaxConstants.SYNTAX_STYLE_JAVA.equals(style))
            return result;

        var ast = cache.getFromCache(this.astKey);
        if (ast == null)
            return result;

        for (IProblem problem : ast.getProblems()) {
            if (cache.allowsPrivilegedAccess(this.astKey) && isAccessProblem(problem.getID())) {
                continue;
            }
            int start = cache.toEditorOffset(this.astKey, problem.getSourceStart());
            int end = cache.toEditorOffset(this.astKey, problem.getSourceEnd());
            if (start < 0 || end < start) {
                continue;
            }
            int line = doc.getDefaultRootElement().getElementIndex(start);
            var notice = new DefaultParserNotice(this, problem.getMessage(), line, start, end - start + 1);
            notice.setLevel(problem.isInfo() ? ParserNotice.Level.INFO : problem.isWarning() ? ParserNotice.Level.WARNING : ParserNotice.Level.ERROR);
            result.addNotice(notice);
        }
        return result;
    }

    private static boolean isAccessProblem(int problemId) {
        return switch (problemId) {
            case IProblem.NotVisibleField,
                 IProblem.NotVisibleMethod,
                 IProblem.NotVisibleConstructor,
                 IProblem.NotAccessibleField,
                 IProblem.NotAccessibleMethod,
                 IProblem.NotAccessibleConstructor -> true;
            default -> false;
        };
    }
}
