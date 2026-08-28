package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerInlineValueHintsTest {
    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.replace(ClassIndex.fromBytes(List.of(classBytes(Object.class), classBytes(String.class))));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void showsVisibleValuesOnlyInsideTheSelectedExecutableBody() {
        String source = """
                class Sample {
                    int inspect(String query, int limit, Object pos) {
                        String folded = query.toLowerCase();
                        if (limit < 1) {
                            return pos.hashCode();
                        }
                        return folded.length();
                    }

                    void unrelated(String query) {
                        System.out.println(query);
                    }
                }
                """;
        DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                1, "Sample.inspect", "Sample", URI.create("file:///Sample.java"), 4, 1
        );
        DebuggerEditorPresentation.Snapshot snapshot = new DebuggerEditorPresentation.Snapshot(frame, List.of(
                presented("query", "\"stone\"", "java.lang.String", DebugEngine.VariableKind.PARAMETER),
                presented("limit", "0", "int", DebugEngine.VariableKind.PARAMETER),
                new DebuggerEditorPresentation.PresentedVariable(
                        variable("pos", "BlockPos@7", "net.minecraft.core.BlockPos",
                                DebugEngine.VariableKind.PARAMETER),
                        new DebugEngine.ValuePreview("x=1, y=64, z=2", "x=1, y=64, z=2")
                ),
                presented("folded", "\"stone\"", "java.lang.String", DebugEngine.VariableKind.LOCAL),
                presented("this", "Sample@1", "Sample", DebugEngine.VariableKind.THIS)
        ));

        Map<Integer, DebuggerInlineValueHints.LineHint> hints = DebuggerInlineValueHints.create(
                ASTCache.rawParse("Sample", source), source, snapshot
        );

        assertTrue(text(hints.get(2)).contains("query: \"stone\""), hints.toString());
        assertTrue(text(hints.get(2)).contains("limit: 0"), hints.toString());
        assertEquals("limit: 0", text(hints.get(4)));
        assertFalse(hints.containsKey(5), "Values from lines after the selected frame must stay hidden");
        assertFalse(hints.containsKey(10), hints.toString());
        assertTrue(hints.values().stream().map(DebuggerInlineValueHintsTest::text)
                .noneMatch(value -> value.contains("this:")));
    }

    private static String text(DebuggerInlineValueHints.LineHint hint) {
        return hint.values().stream().map(DebuggerInlineValueHints.ValueHint::text)
                .reduce((left, right) -> left + "    " + right).orElse("");
    }

    private static DebuggerEditorPresentation.PresentedVariable presented(
            String name,
            String value,
            String type,
            DebugEngine.VariableKind kind
    ) {
        return new DebuggerEditorPresentation.PresentedVariable(
                variable(name, value, type, kind),
                DebugEngine.ValuePreview.NONE
        );
    }

    private static DebugEngine.Variable variable(
            String name,
            String value,
            String type,
            DebugEngine.VariableKind kind
    ) {
        return new DebugEngine.Variable(name, name, value, type, kind, 1, 0, 0);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
