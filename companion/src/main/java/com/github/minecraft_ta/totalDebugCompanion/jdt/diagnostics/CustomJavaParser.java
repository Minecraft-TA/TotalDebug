package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import java.util.List;

/** RSyntax adapter for display state accepted by the editor analysis owner. */
public final class CustomJavaParser extends AbstractParser {
    private List<JavaAnalysis.Problem> problems = List.of();

    public void setProblems(List<JavaAnalysis.Problem> problems) { this.problems = List.copyOf(problems); }

    @Override public ParseResult parse(RSyntaxDocument doc, String style) {
        var result = new DefaultParseResult(this);
        result.setParsedLines(0, doc.getDefaultRootElement().getElementCount() - 1);
        if (!SyntaxConstants.SYNTAX_STYLE_JAVA.equals(style)) return result;
        for (var problem : problems) {
            var span = problem.span();
            if (span.start() < 0 || span.end() > doc.getLength() || span.end() <= span.start()) continue;
            var notice = new DefaultParserNotice(this, problem.message(), doc.getDefaultRootElement().getElementIndex(span.start()),
                    span.start(), span.end() - span.start());
            notice.setLevel(problem.level());
            result.addNotice(notice);
        }
        return result;
    }
}
