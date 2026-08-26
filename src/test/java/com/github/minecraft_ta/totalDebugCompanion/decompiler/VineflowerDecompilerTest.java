package com.github.minecraft_ta.totalDebugCompanion.decompiler;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.ClassBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.fixture.ModernJavaFixture;
import net.minecraft.test.GeneratedNamesFixture;
import net.minecraft.world.level.block.BonemealableBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VineflowerDecompilerTest {
    private final JavaDecompiler decompiler = new VineflowerDecompiler();

    @Test
    void decompilesAndRecompilesModernJava(@TempDir Path outputDirectory) throws Exception {
        DecompilationResult result = decompile(ModernJavaFixture.class);

        assertEquals(DecompilationResult.Status.COMPLETE, result.status(), result.source());
        assertTrue(result.source().contains("sealed interface ModernJavaFixture"), result.source());
        assertTrue(result.source().contains("record Value"), result.source());
        assertTrue(result.source().contains("case String"), result.source());
        assertTrue(result.source().contains("when"), result.source());
        assertTrue(result.source().contains("->"), result.source());
        assertTrue(result.source().contains("stream()"), result.source());
        assertTrue(result.source().contains("class Nested"), result.source());
        assertTrue(result.source().contains("inspect(Object value)"), result.source());
        assertFalse(result.lineMap().isEmpty(), "Vineflower returned no source line mapping");
        assertSortedPairs(result.lineMap().originalToDisplayed());
        assertSortedPairs(result.lineMap().displayedToOriginal());
        assertCompiles(ModernJavaFixture.class.getName(), result.source(), outputDirectory);
    }

    @Test
    void givesGeneratedMinecraftVariablesJadStyleNames() throws Exception {
        DecompilationResult result = decompile(GeneratedNamesFixture.class);

        assertEquals(DecompilationResult.Status.COMPLETE, result.status(), result.source());
        assertTrue(result.source().contains("format(String s, int i)"), result.source());
        assertTrue(result.source().contains("String s1 = s"), result.source());
        assertTrue(result.source().contains("for (int j = 0; j < i; j++)"), result.source());
        assertFalse(result.source().contains("p_100_"), result.source());
        assertFalse(result.source().contains("var3"), result.source());
        assertFalse(result.source().contains("var4"), result.source());
        assertEquals("s", result.variableNames().displayedName("p_100_"));
        assertEquals("i", result.variableNames().displayedName("p_101_"));
        assertEquals("s1", result.variableNames().displayedName("var3"));
        assertEquals("j", result.variableNames().displayedName("var4"));
    }

    @Test
    void appliesParchmentNamesToMinecraftMethods() throws Exception {
        DecompilationResult result = decompile(BonemealableBlock.class);

        assertEquals(DecompilationResult.Status.COMPLETE, result.status(), result.source());
        assertTrue(result.source().contains(
                "performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state)"
        ), result.source());
        assertFalse(result.source().contains("p_220836_"), result.source());
        assertEquals("level", result.variableNames().displayedName("p_220836_"));
        assertEquals("random", result.variableNames().displayedName("p_220837_"));
        assertEquals("pos", result.variableNames().displayedName("p_220838_"));
        assertEquals("state", result.variableNames().displayedName("p_220839_"));
    }

    @Test
    void rejectsMissingTargetBytecode() {
        DecompilationException failure = assertThrows(
                DecompilationException.class,
                () -> this.decompiler.decompile("missing.Type", ignored -> null)
        );

        assertEquals("No bytecode is available for missing.Type", failure.getMessage());
    }

    private DecompilationResult decompile(Class<?> type) throws DecompilationException {
        return this.decompiler.decompile(type.getName(), classPathSource(type.getClassLoader()));
    }

    private static ClassBytecodeSource classPathSource(ClassLoader loader) {
        return className -> {
            String resourceName = normalizeClassName(className) + ".class";
            try (InputStream input = loader.getResourceAsStream(resourceName)) {
                return input == null ? null : input.readAllBytes();
            }
        };
    }

    private static String normalizeClassName(String className) {
        String normalized = className;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized.replace('.', '/');
    }

    private static void assertCompiles(String binaryName, String source, Path outputDirectory) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "Tests must run on a JDK");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject sourceFile = new StringJavaFileObject(binaryName, source);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("--release", "21", "-proc:none", "-d", outputDirectory.toString()),
                    null,
                    List.of(sourceFile)
            ).call();
            assertTrue(success, diagnostics.getDiagnostics().toString() + System.lineSeparator() + source);
        }
    }

    private static void assertSortedPairs(int[] pairs) {
        assertTrue(pairs.length > 0 && pairs.length % 2 == 0);
        for (int i = 2; i < pairs.length; i += 2) {
            assertTrue(pairs[i - 2] <= pairs[i], "Line mapping keys are not sorted");
        }
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        private StringJavaFileObject(String binaryName, String source) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return this.source;
        }
    }
}
