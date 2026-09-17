package com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.tth05.jindex.ClassIndex;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.*;
import org.eclipse.jdt.core.compiler.IProblem;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class CustomJavaParserTest {
    public static class Unused { }
    public static class PrivateAccess {
        private static Object value;
        public static Object visible;
        private PrivateAccess() { }
        private static Object hidden() { return null; }
    }
    private static ClassIndex index;
    @BeforeAll static void bind() throws Exception {
        index = JavaAnalysisFixtures.index(CustomJavaParserTest.class, Unused.class, PrivateAccess.class);
        CompanionClassIndex.set(index);
    }
    @AfterAll static void close() { CompanionClassIndex.clear(); index.close(); }

    @Test void unusedImportsAreStillReportedWhenPrivilegedFieldAccessIsAllowed() throws Exception {
        for (String expression : List.of("PrivateAccess.visible", "PrivateAccess.value", "PrivateAccess.hidden()", "new PrivateAccess()")) {
            String text = "import " + Unused.class.getCanonicalName() + ";\nimport " + PrivateAccess.class.getCanonicalName()
                    + ";\nreturn " + expression + ";";
            var analysis = analyze(text, JavaSnippetSource.body("Proof", text).editorSource());
            var doc = new RSyntaxDocument(SyntaxConstants.SYNTAX_STYLE_JAVA);
            doc.insertString(0, text, null);
            var notices = parser(analysis).parse(doc, SyntaxConstants.SYNTAX_STYLE_JAVA).getNotices();
            assertEquals(1, notices.stream().filter(notice -> notice.getMessage().contains("never used")).count(), expression + ": "
                    + Arrays.toString(analysis.unit().getProblems()));
            assertTrue(notices.stream().noneMatch(notice -> notice.getMessage().contains("not visible")));
        }
    }

    @Test void restoresJdtImportUsageForStaticAndWildcardImports() throws Exception {
        for (String imported : List.of("import " + CustomJavaParserTest.class.getName() + ".*;",
                "import " + PrivateAccess.class.getCanonicalName() + ";\nimport static " + PrivateAccess.class.getCanonicalName() + ".visible;")) {
            boolean staticImport = imported.contains("import static");
            String text = "import " + Unused.class.getCanonicalName() + ";\n" + imported
                    + "\nPrivateAccess.hidden(); return " + (staticImport ? "visible" : "PrivateAccess.visible") + ";";
            var analysis = analyze(text, JavaSnippetSource.body("Proof", text).editorSource());
            var unused = Arrays.stream(analysis.unit().getProblems())
                    .filter(problem -> problem.getID() == IProblem.UnusedImport).toList();
            assertEquals(1, unused.size(), text);
            assertTrue(unused.getFirst().getMessage().contains(".Unused"), unused.toString());
        }
    }

    @Test void semanticErrorsKeepOtherDiagnosticsIncludingUnusedImports() throws Exception {
        for (boolean privileged : List.of(false, true)) {
            String text = "import " + Unused.class.getCanonicalName() + ";\nimport " + PrivateAccess.class.getCanonicalName()
                    + ";\nPrivateAccess.hidden(); return missing;";
            var generated = JavaSnippetSource.body("Proof", text);
            var analysis = analyze(text, new JavaEditorSource(generated.source(), generated.sourceMap(), privileged));
            var doc = new RSyntaxDocument(SyntaxConstants.SYNTAX_STYLE_JAVA); doc.insertString(0, text, null);
            var notices = parser(analysis).parse(doc, SyntaxConstants.SYNTAX_STYLE_JAVA).getNotices();
            assertTrue(notices.stream().anyMatch(notice -> notice.getMessage().contains("missing")));
            assertEquals(!privileged, notices.stream().anyMatch(notice -> notice.getMessage().contains("not visible")));
            assertTrue(notices.stream().anyMatch(notice -> notice.getMessage().contains("never used")));
        }
    }

    @Test void missingBraceHasABoundedEditorNoticeInsteadOfAHiddenWrapperLocation() throws Exception {
        String text = "if (true) {\nreturn 1;";
        var analysis = analyze(text, JavaSnippetSource.body("Proof", text).editorSource());
        var doc = new RSyntaxDocument(SyntaxConstants.SYNTAX_STYLE_JAVA);
        doc.insertString(0, text, null);
        var notices = parser(analysis).parse(doc, SyntaxConstants.SYNTAX_STYLE_JAVA).getNotices();
        assertFalse(notices.isEmpty());
        assertTrue(notices.stream().allMatch(notice -> notice.getOffset() >= 0
                && notice.getOffset() + notice.getLength() <= text.length()));
        assertTrue(notices.stream().anyMatch(notice -> notice.getOffset() == text.length() - 1));
    }
    private static JavaAnalysis analyze(String text, JavaEditorSource source) {
        return JavaAnalysis.parse("Proof", text, source, 0, CompanionClassIndex.identity());
    }

    private static CustomJavaParser parser(JavaAnalysis analysis) {
        var parser = new CustomJavaParser();
        parser.setProblems(analysis.problems());
        return parser;
    }
}
