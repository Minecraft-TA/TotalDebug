package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.tth05.jindex.ClassIndex;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtConfigurationTest {

    @BeforeAll
    static void initializeClassIndex() throws IOException {
        CompanionClassIndex.initialize(ClassIndex.fromBytes(List.of(
                classBytes(Object.class),
                classBytes(String.class),
                classBytes(List.class),
                classBytes(Class.class),
                classBytes(Field.class),
                classBytes(Throwable.class),
                classBytes(Error.class),
                classBytes(Exception.class),
                classBytes(ReflectiveOperationException.class),
                classBytes(NoSuchFieldException.class)
        )));
    }

    @AfterAll
    static void closeClassIndex() {
        CompanionClassIndex.close();
    }

    @Test
    void configuresJava21WithoutPreviewFeatures() {
        var options = new HashMap<String, String>();

        JdtConfiguration.applyJavaCompilerOptions(options);

        assertEquals(JavaCore.VERSION_21, options.get(JavaCore.COMPILER_COMPLIANCE));
        assertEquals(JavaCore.VERSION_21, options.get(JavaCore.COMPILER_SOURCE));
        assertEquals(JavaCore.VERSION_21, options.get(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM));
        assertEquals(JavaCore.DISABLED, options.get(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES));
    }

    @Test
    void parsesJava21RecordPatternsAndGuardedSwitches() {
        var source = """
                package example;
                sealed interface Shape permits Circle {}
                record Circle(int radius) implements Shape {}
                final class Renderer {
                    static String render(Shape shape) {
                        return switch (shape) {
                            case Circle(int radius) when radius > 0 -> "circle";
                            default -> "unknown";
                        };
                    }
                }
                """;

        var unit = ASTCache.rawParse("Renderer", source);
        var syntaxErrors = Arrays.stream(unit.getProblems())
                .filter(problem -> problem.isError() && (problem.getID() & IProblem.Syntax) != 0)
                .map(IProblem::getMessage)
                .toList();

        assertTrue(syntaxErrors.isEmpty(), () -> "Java 21 source produced parser errors: " + syntaxErrors);
    }

    @Test
    void codeSelectCanResolveAgainstTheSyntheticJavaProject() {
        var source = """
                package example;
                final class Renderer {
                    String render() {
                        return "ok";
                    }
                }
                """;
        var unit = ASTCache.rawParse("Renderer", source);
        int stringOffset = source.indexOf("String");

        var elements = assertDoesNotThrow(() -> unit.getTypeRoot().codeSelect(stringOffset, 0));

        assertEquals(1, elements.length);
        assertTrue(elements[0] instanceof IType);
        assertEquals("java.lang.String", ((IType) elements[0]).getFullyQualifiedName());
    }

    @Test
    void semanticDiagnosticsResolveIndexedJavaTypes() {
        var source = """
                import java.util.List;
                final class Test {
                    Object value = new Object();
                    String text = "ok";
                    List<String> values;
                }
                """;

        var unit = ASTCache.rawParse("Test", source);
        var errors = Arrays.stream(unit.getProblems())
                .filter(IProblem::isError)
                .map(IProblem::getMessage)
                .toList();

        assertTrue(errors.isEmpty(), () -> "Indexed Java types produced semantic errors: " + errors);
    }

    @Test
    void semanticDiagnosticsSeeIndexedCheckedExceptions() {
        var source = """
                import java.lang.reflect.Field;
                final class Test {
                    Field find(Class<?> type) {
                        try {
                            return type.getDeclaredField("value");
                        } catch (NoSuchFieldException exception) {
                            return null;
                        }
                    }
                }
                """;

        var errors = Arrays.stream(ASTCache.rawParse("Test", source).getProblems())
                .filter(IProblem::isError)
                .map(IProblem::getMessage)
                .toList();

        assertTrue(errors.isEmpty(), () -> "Indexed checked exceptions produced semantic errors: " + errors);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = Objects.requireNonNull(type.getResourceAsStream(resource), resource)) {
            return stream.readAllBytes();
        }
    }
}
