package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.protocol.Types;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MicrosoftSourceRegistryTest {
    private static final URI SOURCE_URI = URI.create("decompiled:///sample/Outer.java");
    private static final String SOURCE = """
            package sample;

            class Outer {
                void outer() {
                    System.out.println("outer");
                }

                static class Inner {
                    void nested() {
                        System.out.println("nested");
                    }
                }
            }

            class Secondary {
                void secondary() {
                    System.out.println("secondary");
                }
            }
            """;

    @Test
    void resolvesTheInnermostNamedRuntimeClassForEachSourceLine() {
        MicrosoftSourceRegistry registry = registry();

        assertArrayEquals(
                new String[]{"sample.Outer", "sample.Outer$Inner", "sample.Secondary"},
                registry.getFullyQualifiedName(
                        SOURCE_URI.toString(),
                        new int[]{line("outer"), line("nested"), line("secondary")},
                        new int[]{1, 1, 1}
                )
        );
    }

    @Test
    void explicitMethodOwnerOverridesSourceShapeForMethodEntryBreakpoint() {
        MicrosoftSourceRegistry registry = registry();
        int entryLine = line("nested");
        DebugEngine.SourceBreakpoint request = DebugEngine.SourceBreakpoint.methodEntry(
                entryLine - 1,
                entryLine,
                new DebugEngine.MethodTarget("sample.GeneratedOwner", "nested", "()V"),
                null,
                null
        );
        registry.prepareBreakpoints(SOURCE_URI, List.of(request));
        Types.SourceBreakpoint protocol = new Types.SourceBreakpoint(entryLine, null, null);

        JavaBreakpointLocation[] locations = registry.getBreakpointLocations(
                SOURCE_URI.toString(),
                new Types.SourceBreakpoint[]{protocol}
        );

        assertEquals("sample.GeneratedOwner", locations[0].className());
        assertEquals("nested", locations[0].methodName());
        assertEquals("()V", locations[0].methodSignature());
    }

    @Test
    void registersDecompiledSourceContainingJavaTypePatterns() {
        String source = """
                package sample;

                class PatternSource {
                    int length(Object value) {
                        return value instanceof String text ? text.length() : 0;
                    }
                }
                """;
        URI sourceUri = URI.create("decompiled:///sample/PatternSource.java");
        MicrosoftSourceRegistry registry = new MicrosoftSourceRegistry(binaryName -> null);

        assertDoesNotThrow(() -> registry.register(
                new DebugEngine.Source(sourceUri, "sample.PatternSource", source)
        ));
        assertArrayEquals(
                new String[]{"sample.PatternSource"},
                registry.getFullyQualifiedName(sourceUri.toString(), new int[]{5}, new int[]{1})
        );
    }

    @Test
    void hiddenClassesHaveNoSourceEvenWhenTheirNominalOuterClassIsRegistered() {
        MicrosoftSourceRegistry registry = new MicrosoftSourceRegistry(name -> {
            fail("Hidden runtime class must not reach the binary-name source loader: " + name);
            return null;
        });
        registry.register(new DebugEngine.Source(SOURCE_URI, "sample.Outer", SOURCE));
        String hidden = "sample.Outer$Generated/0x0000000800080000";

        assertNull(registry.getSource(hidden, "Outer.java"));
        assertNull(registry.getSourceFileURI(hidden, "Outer.java"));
        assertNull(registry.typeScope(hidden));
        assertEquals("value", registry.displayedVariableName(hidden, "run", "()V", "value"));
        assertEquals(SOURCE_URI.toString(), registry.getSource("sample.Outer$Inner", "Outer.java").getUri());
    }

    @Test
    void aSourceLoadFailureDoesNotPreventResolvingOtherFramesOrRetrying() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        MicrosoftSourceRegistry registry = new MicrosoftSourceRegistry(name -> {
            if (attempts.getAndIncrement() == 0) throw new IOException("Damaged source cache");
            return new DebugEngine.Source(SOURCE_URI, "sample.Outer", SOURCE);
        });

        assertNull(registry.getSource("sample.Outer", "Outer.java"));
        assertEquals(SOURCE_URI.toString(), registry.getSource("sample.Outer", "Outer.java").getUri());
    }

    @Test
    void unparseableSourceDoesNotPreventInspection() {
        MicrosoftSourceRegistry registry = new MicrosoftSourceRegistry(name ->
                new DebugEngine.Source(SOURCE_URI, "sample.Outer", "class Outer { void broken( }"));

        assertNull(registry.getSource("sample.Outer", "Outer.java"));
    }

    @Test
    void interruptedSourceLookupPreservesTheInterrupt() {
        MicrosoftSourceRegistry registry = new MicrosoftSourceRegistry(name -> {
            throw new InterruptedException("Source lookup interrupted");
        });
        try {
            assertNull(registry.getSource("sample.Outer", "Outer.java"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static MicrosoftSourceRegistry registry() {
        MicrosoftSourceRegistry registry = new MicrosoftSourceRegistry(binaryName -> null);
        registry.register(new DebugEngine.Source(SOURCE_URI, "sample.Outer", SOURCE));
        return registry;
    }

    private static int line(String marker) {
        String[] lines = SOURCE.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains("\"" + marker + "\"")) {
                return index + 1;
            }
        }
        throw new AssertionError("Missing source marker " + marker);
    }
}
