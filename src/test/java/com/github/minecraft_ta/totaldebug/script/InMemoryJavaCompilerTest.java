package com.github.minecraft_ta.totaldebug.script;

import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryJavaCompilerTest {
    @TempDir
    Path temporaryDirectory;

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

    @Test
    void doesNotRunAnnotationProcessorsFromTheGameClasspath() throws Exception {
        Path processorJar = this.temporaryDirectory.resolve("failing-processor.jar");
        String processorResource = FailingAnnotationProcessor.class.getName().replace('.', '/') + ".class";
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(processorJar))) {
            output.putNextEntry(new ZipEntry(processorResource));
            try (var input = Objects.requireNonNull(
                    FailingAnnotationProcessor.class.getClassLoader().getResourceAsStream(processorResource),
                    processorResource
            )) {
                output.write(input.readAllBytes());
            }
            output.closeEntry();
            output.putNextEntry(new ZipEntry("META-INF/services/javax.annotation.processing.Processor"));
            output.write(FailingAnnotationProcessor.class.getName().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Map<String, byte[]> result = this.compiler.compile(
                "public class ProcessorFixture {}",
                "ProcessorFixture",
                processorJar.toString()
        );

        assertTrue(result.containsKey("ProcessorFixture"));
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
