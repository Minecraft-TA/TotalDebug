package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerBreakpointResolverTest {
    @BeforeAll
    static void initializeIndex() throws Exception {
        try (var stream = Object.class.getResourceAsStream("/java/lang/Object.class")) {
            CompanionClassIndex.set(ClassIndex.fromBytes(java.util.List.of(stream.readAllBytes())));
        }
    }

    @AfterAll
    static void closeIndex() {
        CompanionClassIndex.get().close();
        CompanionClassIndex.clear();
    }

    private static final String SOURCE = """
            package example;
            public class Test {
                public int run() {
                    return 3;
                }
            }
            """;

    @Test
    void editorAndRemoteCallsResolveTheSameMethodEntryAndExecutableLine() {
        DebugEngine.Source source = source(SourceLineMap.fromOriginalToDisplayed(new int[]{40, 4}));
        var unit = JavaAst.parse("Test", SOURCE);
        var remote = DebuggerBreakpointResolver.resolve(source, 3, "true", "5").orElseThrow();
        assertEquals(remote, DebuggerBreakpointResolver.resolve(source, unit, 3, "true", "5").orElseThrow());
        assertTrue(remote.isMethodEntry());
        assertEquals(3, remote.line());
        assertEquals(4, remote.debuggerLine());
        assertEquals("run", remote.method().name());
        assertEquals("example.Test", remote.method().ownerClassName());
        assertFalse(DebuggerBreakpointResolver.resolve(source, 4, null, null).orElseThrow().isMethodEntry());
        assertTrue(DebuggerBreakpointResolver.resolve(source, 6, null, null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> DebuggerBreakpointResolver.resolve(source, 99, null, null));
    }

    @Test
    void unmappedSourceUsesItsDisplayedLine() {
        var request = DebuggerBreakpointResolver.resolve(source(SourceLineMap.empty()), 4, null, null).orElseThrow();
        assertEquals(4, request.debuggerLine());
        assertFalse(request.isMethodEntry());
    }

    @Test
    void lineBreakpointsDoNotRequireBindingsForUnrelatedMethods() {
        String text = """
                package example;
                class Test {
                    void unresolved(MissingType value) { }
                    int run() {
                        return 3;
                    }
                }
                """;
        var source = new DebugEngine.Source(URI.create("decompiled:///example/Test.java"), "example.Test", text,
                SourceLineMap.fromOriginalToDisplayed(new int[]{40, 5}));
        assertEquals(5, DebuggerBreakpointResolver.resolve(source, 5, null, null).orElseThrow().line());
        assertTrue(DebuggerBreakpointResolver.resolve(source, 4, null, null).orElseThrow().isMethodEntry());
    }

    private static DebugEngine.Source source(SourceLineMap map) {
        return new DebugEngine.Source(URI.create("decompiled:///example/Test.java"), "example.Test", SOURCE, map);
    }
}
