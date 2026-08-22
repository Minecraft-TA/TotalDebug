package com.github.minecraft_ta.totaldebug.script;

import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryJavaCompilerTest {
    private final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();

    @Test
    void compilesAgainstMinecraftFromAnExplicitClasspath() throws Exception {
        String source = """
                import net.minecraft.world.level.block.Block;
                public class CompilerFixture {
                    public Block identity(Block block) {
                        return block;
                    }
                }
                """;

        Map<String, byte[]> result = this.compiler.compile(
                source,
                "CompilerFixture",
                classpathFor(Block.class)
        );

        assertTrue(result.containsKey("CompilerFixture"));
        assertTrue(result.get("CompilerFixture").length > 0);
    }

    @Test
    void reportsJavacDiagnosticsOnFailure() {
        InMemoryCompilationException exception = assertThrows(
                InMemoryCompilationException.class,
                () -> this.compiler.compile(
                        "public class Broken { MissingType value; }",
                        "Broken",
                        ""
                )
        );

        assertTrue(exception.getMessage().contains("MissingType"));
        assertTrue(exception.getMessage().contains("line 1"));
    }

    private static String classpathFor(Class<?>... types) {
        return List.of(types).stream()
                .map(InMemoryJavaCompilerTest::codeSource)
                .distinct()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
