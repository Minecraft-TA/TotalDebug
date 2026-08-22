package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtConfigurationTest {

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
        var errors = Arrays.stream(unit.getProblems())
                .filter(IProblem::isError)
                .map(IProblem::getMessage)
                .toList();

        assertTrue(errors.isEmpty(), () -> "Java 21 source produced parser errors: " + errors);
    }
}
