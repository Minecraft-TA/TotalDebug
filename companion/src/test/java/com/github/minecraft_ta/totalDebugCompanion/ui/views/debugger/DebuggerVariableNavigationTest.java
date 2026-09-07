package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerVariableNavigationTest {
    private static final URI SOURCE_URI = URI.create("decompiled:///example/Target.java");
    private static final DebugEngine.Source SOURCE = new DebugEngine.Source(
            SOURCE_URI,
            "example.Target",
            """
                    package example;

                    class Target {
                        void run(String input) {
                            int count = 1;
                            System.out.println(input + count);
                        }
                    }
                    """
    );
    private static final DebugEngine.StackFrame FRAME = new DebugEngine.StackFrame(
            1, "Target.run", "example.Target", SOURCE_URI, 6, 1
    );

    @Test
    void resolvesParametersAndLocalsWithinThePausedExecutable() {
        assertEquals(
                new NavigationTarget.RuntimeLine("example.Target", 4),
                DebuggerVariableNavigation.declarationTarget(
                        SOURCE,
                        FRAME,
                        variable("input", "java.lang.String", DebugEngine.VariableKind.PARAMETER),
                        null
                ).orElseThrow()
        );
        assertEquals(
                new NavigationTarget.RuntimeLine("example.Target", 5),
                DebuggerVariableNavigation.declarationTarget(
                        SOURCE,
                        FRAME,
                        variable("count", "int", DebugEngine.VariableKind.LOCAL),
                        null
                ).orElseThrow()
        );
    }

    @Test
    void resolvesFieldOwnersAndReferenceTypes() {
        DebugEngine.Variable field = new DebugEngine.Variable(
                "value", "value (example.Base)", "this.value", "7", "int",
                DebugEngine.VariableKind.FIELD, 12, 0, 0, 0
        );
        assertEquals(
                new NavigationTarget.RuntimeDeclaration(new RuntimeMember.Field("example.Base", "value")),
                DebuggerVariableNavigation.declarationTarget(SOURCE, FRAME, field, null).orElseThrow()
        );
        assertEquals(
                new NavigationTarget.RuntimeClass("java.lang.String"),
                DebuggerVariableNavigation.typeTarget(
                        FRAME,
                        variable("names", "java.lang.String[][]", DebugEngine.VariableKind.LOCAL)
                ).orElseThrow()
        );
        assertTrue(DebuggerVariableNavigation.typeTarget(
                FRAME,
                variable("count", "int", DebugEngine.VariableKind.LOCAL)
        ).isEmpty());
    }

    @Test
    void selectsTheVisibleDeclarationWhenLocalSlotsReuseAName() {
        DebugEngine.Source source = new DebugEngine.Source(
                SOURCE_URI,
                "example.Target",
                """
                        package example;

                        class Target {
                            void run(boolean first) {
                                if (first) {
                                    int value = 1;
                                    System.out.println(value);
                                } else {
                                    int value = 2;
                                    System.out.println(value);
                                }
                            }
                        }
                        """
        );
        DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                1, "Target.run", "example.Target", SOURCE_URI, 10, 1
        );

        assertEquals(
                new NavigationTarget.RuntimeLine("example.Target", 9),
                DebuggerVariableNavigation.declarationTarget(
                        source,
                        frame,
                        variable("value", "int", DebugEngine.VariableKind.LOCAL),
                        null
                ).orElseThrow()
        );
    }

    @Test
    void fallsBackToTheFrameClassForStandaloneStaticFields() {
        DebugEngine.Variable field = new DebugEngine.Variable(
                "COUNT", "COUNT", "example.Target.COUNT", "7", "int",
                DebugEngine.VariableKind.FIELD, 12, 0, 0, 0
        );

        assertEquals(
                new NavigationTarget.RuntimeDeclaration(
                        new RuntimeMember.Field("example.Target", "COUNT")
                ),
                DebuggerVariableNavigation.declarationTarget(SOURCE, FRAME, field, null).orElseThrow()
        );
    }

    private static DebugEngine.Variable variable(
            String name,
            String type,
            DebugEngine.VariableKind kind
    ) {
        return new DebugEngine.Variable(name, name, name, "", type, kind, 9, 0, 0, 0);
    }
}
