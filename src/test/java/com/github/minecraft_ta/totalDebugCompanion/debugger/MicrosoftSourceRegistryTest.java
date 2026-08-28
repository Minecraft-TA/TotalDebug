package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.protocol.Types;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
