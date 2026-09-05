package com.github.minecraft_ta.totaldebug.evaluation;

import com.github.minecraft_ta.totaldebug.script.ScriptProgram;
import com.github.minecraft_ta.totaldebug.script.FailingAnnotationProcessor;

import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void compilesImportedProgramWhenAClassShadowsThePackageRoot() throws Exception {
        Path shadowJar = this.temporaryDirectory.resolve("package-root-shadow.jar");
        Map<String, byte[]> shadow = this.compiler.compile("public final class com {}", "com", "");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(shadowJar))) {
            output.putNextEntry(new ZipEntry("com.class"));
            output.write(shadow.get("com"));
            output.closeEntry();
        }

        Map<String, byte[]> result = this.compiler.compile(
                """
                        import com.github.minecraft_ta.totaldebug.script.ScriptProgram;
                        public final class CompanionExpression extends ScriptProgram {
                            @Override
                            public Object run() throws Throwable {
                                return 42;
                            }
                        }
                """,
                "CompanionExpression",
                shadowJar + File.pathSeparator + codeSource(ScriptProgram.class)
        );

        assertTrue(result.containsKey("CompanionExpression"));
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
    void emitsSourceLinesAndLocalVariablesForDebugging() throws Exception {
        Map<String, byte[]> result = this.compiler.compile(
                """
                        public class DebugMetadataFixture {
                            public int increment(int input) {
                                int result = input + 1;
                                return result;
                            }
                        }
                        """,
                "DebugMetadataFixture",
                ""
        );

        AtomicBoolean sourceFile = new AtomicBoolean();
        AtomicBoolean lineNumber = new AtomicBoolean();
        AtomicBoolean inputVariable = new AtomicBoolean();
        AtomicBoolean resultVariable = new AtomicBoolean();
        new ClassReader(result.get("DebugMetadataFixture")).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitSource(String source, String debug) {
                sourceFile.set("DebugMetadataFixture.java".equals(source));
            }

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals("increment")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLineNumber(int line, Label start) {
                        lineNumber.set(true);
                    }

                    @Override
                    public void visitLocalVariable(
                            String name,
                            String descriptor,
                            String signature,
                            Label start,
                            Label end,
                            int index
                    ) {
                        if (name.equals("input")) {
                            inputVariable.set(true);
                        }
                        if (name.equals("result")) {
                            resultVariable.set(true);
                        }
                    }
                };
            }
        }, 0);

        assertTrue(sourceFile.get(), "Compiled script has no SourceFile attribute");
        assertTrue(lineNumber.get(), "Compiled script has no LineNumberTable");
        assertTrue(inputVariable.get(), "Compiled script has no input local variable");
        assertTrue(resultVariable.get(), "Compiled script has no result local variable");
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
