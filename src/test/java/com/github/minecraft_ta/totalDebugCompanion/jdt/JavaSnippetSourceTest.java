package com.github.minecraft_ta.totalDebugCompanion.jdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSnippetSourceTest {
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

                String text = \"\"\"
                import fake.TextBlockType;
                \"\"\";
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
        org.junit.jupiter.api.Assertions.assertThrows(
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
