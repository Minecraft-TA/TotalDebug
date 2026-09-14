package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GeneratedSourceNavigationTest {
    @BeforeAll
    static void initializeIndex() throws Exception {
        var bytes = new ArrayList<byte[]>();
        for (Class<?> type : List.of(Object.class, Record.class, String.class,
                java.lang.annotation.Annotation.class, java.util.function.Supplier.class)) {
            try (var input = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) {
                bytes.add(input.readAllBytes());
            }
        }
        CompanionClassIndex.set(ClassIndex.fromBytes(bytes));
    }

    @AfterAll
    static void closeIndex() {
        CompanionClassIndex.get().close();
        CompanionClassIndex.clear();
    }

    @Test
    void recordBackingFieldsAndAccessorsNavigateToTheComponent() {
        String source = "package example;\npublic record Data(String[] values) {}";
        assertEquals(source.indexOf("String[] values"), usage(source, "example.Data", "values",
                "()[Ljava/lang/String;", ReferenceQuery.classReference("java.lang.String")));
        assertEquals(source.indexOf("String[] values"), new SourceDocument("example.Data", source).usage(ReferenceLocation.field("example.Data", "values", "[Ljava/lang/String;"), ReferenceQuery.classReference("java.lang.String")).caret());
        assertEquals(source.indexOf("values)"), new SourceDocument("example.Data", source).navigate(new RuntimeMember.Method("example.Data", "values", "()[Ljava/lang/String;")).caret());
    }

    @Test
    void omittedMethodsFallBackToTheTypeRatherThanAnUnrelatedMatchingReference() {
        String source = "package example;\npublic record Data(String value) { String unrelated() { return value.trim(); } }";
        for (String[] method : List.of(new String[]{"hashCode", "()I"},
                new String[]{"equals", "(Ljava/lang/Object;)Z"},
                new String[]{"toString", "()Ljava/lang/String;"},
                new String[]{"<init>", "(Ljava/lang/String;)V"},
                new String[]{"lambda$static$16", "(I)[Ljava/lang/String;"},
                new String[]{"bridge", "()Ljava/lang/Object;"})) {
            assertEquals(source.indexOf("Data("), usage(source, "example.Data", method[0], method[1],
                    ReferenceQuery.methodReference("java.lang.String", "trim", "()Ljava/lang/String;")));
            assertEquals(source.indexOf("Data("), new SourceDocument("example.Data", source).navigate(new RuntimeMember.Method("example.Data", method[0], method[1])).caret());
        }
    }

    @Test
    void staticInitializersSearchOnlyTheirExecutableSource() {
        String source = """
                package example;
                class Use {
                    java.util.function.Supplier<String> instance = () -> "needle";
                    static java.util.function.Supplier<String> supplier = () -> "needle";
                    static final String constant = "needle";
                    static String first = "other";
                    static String second = "needle";
                    static { second = "block"; }
                }
                """;
        assertEquals(source.indexOf("\"needle\";", source.indexOf("static String second")),
                usage(source, "example.Use", "<clinit>", "()V", ReferenceQuery.stringLiteral("needle")));
        assertEquals(source.indexOf("\"block\""),
                usage(source, "example.Use", "<clinit>", "()V", ReferenceQuery.stringLiteral("block")));
        assertEquals(source.indexOf("Use {"),
                usage(source, "example.Use", "<clinit>", "()V", ReferenceQuery.stringLiteral("missing")));
    }

    @Test
    void annotationConstantsDoNotShadowExecutableStaticInitializers() {
        String source = """
                package example;
                @interface Marker {
                    String CONSTANT = "needle";
                    String EXECUTED = "needle".trim();
                }
                """;
        assertEquals(source.indexOf("\"needle\".trim"),
                usage(source, "example.Marker", "<clinit>", "()V", ReferenceQuery.stringLiteral("needle")));
    }

    @Test
    void ordinaryMethodsDoNotSearchInsideAnotherBytecodeMethodLambda() {
        String source = """
                package example;
                class Use {
                    String run() {
                        java.util.function.Supplier<String> supplier = () -> "needle";
                        return "needle";
                    }
                }
                """;
        assertEquals(source.indexOf("\"needle\"", source.indexOf("return")),
                usage(source, "example.Use", "run", "()Ljava/lang/String;", ReferenceQuery.stringLiteral("needle")));
    }

    @Test
    void constructorsSearchTheirOwnBodyThenInstanceInitializationWithoutFollowingThisCalls() {
        String source = """
                package example;
                class Use {
                    static String shared = "static-only";
                    java.util.function.Supplier<String> supplier = () -> "lambda-only";
                    final String constant = "constant";
                    String value = "needle";
                    { value += "block"; }
                    Use() { }
                    Use(int ignored) { this(); value = "chained"; }
                    Use(boolean ignored) { super(); value = "needle"; }
                }
                """;
        assertEquals(source.indexOf("\"needle\""), usage(source, "example.Use", "<init>", "()V", ReferenceQuery.stringLiteral("needle")));
        assertEquals(source.indexOf("\"block\""), usage(source, "example.Use", "<init>", "()V", ReferenceQuery.stringLiteral("block")));
        assertEquals(source.indexOf("\"constant\""), usage(source, "example.Use", "<init>", "()V", ReferenceQuery.stringLiteral("constant")));
        assertEquals(source.lastIndexOf("\"needle\""), usage(source, "example.Use", "<init>", "(Z)V", ReferenceQuery.stringLiteral("needle")));
        assertEquals(source.indexOf("\"chained\""), usage(source, "example.Use", "<init>", "(I)V", ReferenceQuery.stringLiteral("chained")));
        assertEquals(source.indexOf("Use(int"), usage(source, "example.Use", "<init>", "(I)V", ReferenceQuery.stringLiteral("needle")));
        for (String unrelated : List.of("static-only", "lambda-only")) {
            assertEquals(source.indexOf("Use()"), usage(source, "example.Use", "<init>", "()V", ReferenceQuery.stringLiteral(unrelated)));
        }
        assertEquals(source.indexOf("Use {"), usage(source, "example.Use", "<init>", "(D)V", ReferenceQuery.stringLiteral("needle")));
    }

    @Test
    void navigatesNestedTypesAndAnnotationElements() {
        String source = """
                package example;
                class Outer {
                    void run() { System.out.println("wrong"); }
                    interface Nested { String run(); }
                    @interface Marker { String value() default "annotation"; }
                }
                """;
        assertEquals(source.indexOf("String run"),
                usage(source, "example.Outer$Nested", "run", "()Ljava/lang/String;", ReferenceQuery.stringLiteral("missing")));
        assertEquals(source.indexOf("\"annotation\""),
                usage(source, "example.Outer$Marker", "value", "()Ljava/lang/String;", ReferenceQuery.stringLiteral("annotation")));
        assertEquals(source.indexOf("Nested {"),
                usage(source, "example.Outer$Nested", "missing", "()V", ReferenceQuery.stringLiteral("wrong")));
    }

    private static int usage(String source, String owner, String name, String descriptor, ReferenceQuery query) {
        return new SourceDocument(owner, source).usage(ReferenceLocation.method(owner, name, descriptor), query).caret();
    }
}
