package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SourceFileNavigationTest {
    private static final String SOURCE = """
            package example;

            class Use {
                static final class Target {
                    int value;

                    Target() {
                    }

                    void run() {
                    }
                }

                void test(Target target) {
                    class Local {
                        void hidden(Target hidden) {
                            hidden.run();
                        }
                    }
                    target.value++;
                    target.run();
                    System.out.println("indexed text");
                    new Target();
                }
            }
            """;
    private static final ReferenceLocation METHOD_SITE = ReferenceLocation.method(
            "example.Use",
            "test",
            "(Lexample/Use$Target;)V"
    );

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.set(ClassIndex.fromBytes(List.of(classBytes(Object.class))));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.get().close();
        CompanionClassIndex.clear();
    }

    @Test
    void locatesExactClassFieldMethodAndConstructorOccurrences() {
        assertEquals(
                SOURCE.indexOf("Target target"),
                offset(ReferenceQuery.classReference("example.Use$Target"))
        );
        assertEquals(
                SOURCE.indexOf("value++"),
                offset(ReferenceQuery.fieldReference("example.Use$Target", "value", "I"))
        );
        assertEquals(
                SOURCE.indexOf("target.run") + "target.".length(),
                offset(ReferenceQuery.methodReference("example.Use$Target", "run", "()V"))
        );
        assertEquals(
                SOURCE.indexOf("Target();", SOURCE.indexOf("new Target")),
                offset(ReferenceQuery.methodReference("example.Use$Target", "<init>", "()V"))
        );
        assertEquals(
                SOURCE.indexOf("\"indexed text\""),
                offset(ReferenceQuery.stringLiteral("indexed text"))
        );
    }

    @Test
    void fallsBackToTheContainingDeclarationWhenNoSourceNodeRepresentsTheEdge() {
        assertEquals(
                SOURCE.indexOf("void test"),
                offset(ReferenceQuery.fieldReference("example.Use$Target", "missing", "I"))
        );
    }

    @Test
    void targetsTheExactMemberNameInsteadOfItsAnnotationOrDeclarationStart() {
        String source = """
                package sample;

                class Target {
                    @Deprecated
                    void apply(int value) {
                    }

                    @Deprecated
                    int first, selected;
                }
                """;

        assertEquals(
                source.indexOf("apply(int"),
                SourceFileNavigation.memberOffset(
                        source,
                        new RuntimeMember.Method("sample.Target", "apply", "(I)V")
                )
        );
        assertEquals(
                source.indexOf("selected;"),
                SourceFileNavigation.memberOffset(source, new RuntimeMember.Field("sample.Target", "selected"))
        );
    }

    @Test
    void targetsTheTopLevelTypeNameInsteadOfThePackageAndImports() {
        String source = """
                package sample;

                import java.util.List;
                import java.util.Map;

                /** Type documentation that should remain visible above the destination. */
                @Deprecated
                public class Target {
                }
                """;

        assertEquals(source.indexOf("Target {"), SourceFileNavigation.topLevelTypeOffset(source));
    }

    private static int offset(ReferenceQuery query) {
        return SourceFileNavigation.usageOffset(SOURCE, METHOD_SITE, query);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
