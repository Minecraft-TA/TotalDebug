package com.github.minecraft_ta.totalDebugCompanion.jdt;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSnippetSourceTest {
    @Test
    void multilineIncompleteImportDoesNotHideTheNextImport() {
        String text = "import java.\nutil.List\nimport java.util.Map;\nreturn 1;";
        var imports = JavaImports.read(text);
        assertEquals(2, imports.size());
        assertEquals("java.util.List", imports.get(0).name());
        assertFalse(imports.get(0).complete());
        assertEquals("java.util.Map", imports.get(1).name());
        assertTrue(imports.get(1).complete());
        var generated = JavaSnippetSource.body("Proof", text);
        assertTrue(generated.source().indexOf("import java.util.Map;") < generated.source().indexOf("public Object run()"));
    }

    @Test
    void brokenImportDoesNotConsumeCallsArraysOrGenericDeclarations() {
        for (String body : new String[]{"String.valueOf(1);", "String[] values = null;", "List<String> values = null;"}) {
            String text = "import java.util.\n" + body;
            var generated = JavaSnippetSource.body("Proof", text);
            assertTrue(generated.source().indexOf(body) > generated.source().indexOf("public Object run()"), generated.source());
            assertEquals(text.indexOf(body), generated.sourceMap().toEditorOffset(generated.source().indexOf(body)));
        }
    }

    @Test void incompleteImportStaysInTheHeaderWithoutConsumingTheFollowingStatement() {
        for (String declaration : new String[]{"import java.util.List", "import java.util.", "import static java.lang.Math.PI", "import"}) {
            String editor = declaration + "\nString text = \"stable\";\nreturn text;";
            var generated = JavaSnippetSource.body("Proof", editor);
            assertTrue(generated.source().indexOf(declaration) < generated.source().indexOf("class Proof"));
            assertTrue(generated.source().indexOf("String text") > generated.source().indexOf("public Object run()"));
            assertEquals(editor.indexOf("String text"), generated.sourceMap().toEditorOffset(generated.source().indexOf("String text")));
        }
    }

    @Test void multilineAndCommentedImportsStillRoundTripAndPreserveTheirErrors() {
        String editor = "import java.\nutil./* name */List;\nimport static java.lang.Math.*;\nimport java.util.Map\nreturn 1;";
        var generated = JavaSnippetSource.body("Proof", editor);
        assertTrue(generated.source().contains("import java.\nutil./* name */List;"));
        assertTrue(generated.source().contains("import java.util.Map\n"), "Do not silently insert a missing semicolon");
        assertTrue(generated.source().indexOf("import java.util.Map") < generated.source().indexOf("class Proof"));
        assertTrue(generated.source().indexOf("return 1;") > generated.source().indexOf("public Object run()"));
    }
    @Test void diagnosticsDoNotMistakeGeneratedImportInsertionPointsForUserCode() {
        var generated = JavaSnippetSource.body("Proof", "return 1;");
        int wrapperImport = generated.source().indexOf("import ");
        assertEquals(0, generated.sourceMap().toEditorOffset(wrapperImport), "Completion may insert user imports here");
        assertEquals(-1, generated.sourceMap().toEditorDiagnosticOffset(wrapperImport, false));
        assertEquals(-1, generated.sourceMap().toEditorDiagnosticOffset(wrapperImport, true));
        assertEquals("return 1;".length(), generated.sourceMap().toEditorDiagnosticOffset(generated.source().length(), true));
        assertEquals(-1, generated.sourceMap().toEditorDiagnosticOffset(generated.source().length(), false));
    }
    @Test
    void detectsExpressionsIndependentlyOfEditorLineCount() {
        assertEquals(JavaSnippetSource.Mode.EXPRESSION, JavaSnippetSource.detectMode("getServer()"));
        assertEquals(JavaSnippetSource.Mode.EXPRESSION, JavaSnippetSource.detectMode("/* receiver */ getServer() // result"));
        assertEquals(JavaSnippetSource.Mode.EXPRESSION, JavaSnippetSource.detectMode("List.of(\n 1,\n 2\n)"));
        assertEquals(JavaSnippetSource.Mode.EXPRESSION, JavaSnippetSource.detectMode("import java.util.List;\nList.of(1)"));
        assertEquals(JavaSnippetSource.Mode.EXPRESSION, JavaSnippetSource.detectMode("\"a;b\""));
        assertEquals(JavaSnippetSource.Mode.BODY, JavaSnippetSource.detectMode("var value = 1;\nreturn value;"));
        assertEquals(JavaSnippetSource.Mode.BODY, JavaSnippetSource.detectMode("logln(1); logln(2);"));
        assertEquals(JavaSnippetSource.Mode.BODY, JavaSnippetSource.detectMode("getServer(); return 2;"));
        assertEquals(JavaSnippetSource.Mode.BODY, JavaSnippetSource.detectMode("for (int i = 0; i < 2; i++) logln(i);"));
    }
    @Test
    void bodySourceKeepsOnlyTheUserSnippetVisible() {
        String editor = """
                import java.util.List;

                logln(List.of("proof"));
                return List.of(1, 2);
                """;

        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body("Proof", editor);

        assertTrue(generated.source().contains("import java.util.List;"));
        assertTrue(generated.source().contains("import " + JavaSnippetSource.PROGRAM_TYPE + ";"));
        assertTrue(generated.source().contains("class Proof extends ScriptProgram"));
        assertTrue(generated.source().contains("public Object run() throws Throwable"));
        assertTrue(generated.source().contains("return List.of(1, 2);"));
        assertFalse(generated.source().contains("BaseScript"));
        assertEquals(
                editor.indexOf("List.of(1, 2)"),
                generated.sourceMap().toEditorOffset(generated.source().indexOf("List.of(1, 2)"))
        );
        assertTrue(generated.editorSource().privilegedAccess());
    }

    @Test
    void mapsCompilerLinesBackAcrossTheHiddenWrapper() {
        String editor = "return missingValue;\n";
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body("Proof", editor);
        int generatedLine = generated.source().substring(0, generated.source().indexOf("missingValue"))
                .split("\\R", -1).length;

        assertEquals(
                "line 1: cannot find symbol",
                generated.mapDiagnostics("line " + generatedLine + ": cannot find symbol")
        );
    }

    @Test
    void importsTheProgramTypeSoAnObfuscatedComClassCannotShadowItsPackage() {
        String source = JavaSnippetSource.expression("Proof", "42").source();

        assertTrue(source.contains("import " + JavaSnippetSource.PROGRAM_TYPE + ";"));
        assertTrue(source.contains("class Proof extends ScriptProgram"));
        assertFalse(source.contains("extends " + JavaSnippetSource.PROGRAM_TYPE));
    }

    @Test
    void canHideInjectedImportLinesFromExpressionDiagnostics() {
        String editor = "import java.util.List;\nmissingValue";
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.expression("Proof", editor);
        int generatedLine = generated.source().substring(0, generated.source().indexOf("missingValue"))
                .split("\\R", -1).length;

        assertEquals(
                "line 1: cannot find symbol",
                generated.mapDiagnostics("line " + generatedLine + ": cannot find symbol", 1)
        );
    }

    @Test
    void mapsTheFirstBodyOffsetAfterAnImportToTheSnippetBody() {
        String editor = "import java.util.List;\nList.of(1);";
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body("Proof", editor);
        int editorBody = editor.indexOf("List.of");
        int generatedBody = generated.source().lastIndexOf("List.of");

        assertEquals(generatedBody, generated.sourceMap().toGeneratedOffset(editorBody));
        assertEquals(editorBody, generated.sourceMap().toEditorOffset(generatedBody));
    }

    @Test
    void extractsOnlyRealPrefixImports() {
        String editor = """
                /*
                 * import fake.CommentType;
                 */
                import java.util.List;

                String text = \"""
                import fake.TextBlockType;
                \""";
                return List.of(text);
                """;

        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body("Proof", editor);
        int bodyStart = generated.source().indexOf("public Object run()");

        assertTrue(generated.source().indexOf("import java.util.List;") < bodyStart);
        assertTrue(generated.source().indexOf("import fake.CommentType;") > bodyStart);
        assertTrue(generated.source().indexOf("import fake.TextBlockType;") > bodyStart);
    }

    @Test
    void validatesExecutionSizeWithoutBreakingSourceGeneration() {
        JavaSnippetSource.GeneratedSource generated = JavaSnippetSource.body(
                "Proof",
                "log(\"" + "x".repeat(JavaSnippetSource.MAX_SOURCE_BYTES) + "\");"
        );

        assertTrue(generated.sourceBytes() > JavaSnippetSource.MAX_SOURCE_BYTES);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                generated::requireExecutableSize
        );
    }

    @Test
    void jdtMirrorMatchesTheRuntimeEntryPointContract() {
        String source = ScriptProgramSource.text();

        assertTrue(source.contains("package com.github.minecraft_ta.totaldebug.script"));
        assertTrue(source.contains("public abstract Object run() throws Throwable"));
        assertTrue(source.contains("protected final Object noResult()"));
        assertFalse(source.contains("resultValue"));
        assertFalse(source.contains("setAccessible"));
    }
}
