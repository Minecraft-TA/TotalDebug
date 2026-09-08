package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryJavaCompilerSessionTest {
    @TempDir Path temporaryDirectory;

    @Test
    void repeatedCompilesKeepOutputsAndDiagnosticsIndependent() throws Exception {
        try (var compiler = new InMemoryJavaCompiler()) {
            assertEquals(2, compiler.compile("public class First {} class OldHelper {}", "First", "").size());
            InMemoryCompilationException firstError = assertThrows(InMemoryCompilationException.class,
                    () -> compiler.compile("public class Broken { MissingFirst value; }", "Broken", ""));
            assertTrue(firstError.getMessage().contains("MissingFirst"));
            InMemoryCompilationException secondError = assertThrows(InMemoryCompilationException.class,
                    () -> compiler.compile("public class Broken { MissingSecond value; }", "Broken", ""));
            assertTrue(secondError.getMessage().contains("MissingSecond"));
            assertFalse(secondError.getMessage().contains("MissingFirst"));
            Map<String, byte[]> output = compiler.compile("public class First {}", "First", "");
            assertEquals(Set.of("First"), output.keySet());
        }
    }

    @Test
    void classpathOrderChangesSelectTheNewDefinitionAndReleaseOldArchives() throws Exception {
        Path first = dependency("first.jar", 1);
        Path second = dependency("second.jar", 2);
        try (var compiler = new InMemoryJavaCompiler()) {
            assertEquals(1, compileValue(compiler, first + File.pathSeparator + second));
            assertEquals(2, compileValue(compiler, second + File.pathSeparator + first));
            compiler.compile("public class Unrelated {}", "Unrelated", "");
            // Windows denies removing archives while javac retains open handles.
            Files.delete(first);
            Files.delete(second);
        }
    }

    @Test
    void noticesChangedArchiveAtTheSamePath() throws Exception {
        Path archive = dependency("dependency.jar", 1);
        try (var compiler = new InMemoryJavaCompiler()) {
            assertEquals(1, compileValue(compiler, archive.toString()));
            FileTime previousTime = Files.getLastModifiedTime(archive);
            dependency("dependency.jar", 2);
            Files.setLastModifiedTime(archive, FileTime.fromMillis(previousTime.toMillis() + 2_000));
            assertEquals(2, compileValue(compiler, archive.toString()));
        }
    }

    @Test
    void rereadsChangedClassesInsideAnExistingPackageDirectory() throws Exception {
        Path directory = Files.createDirectory(this.temporaryDirectory.resolve("classes"));
        Path packageDirectory = Files.createDirectory(directory.resolve("fixture"));
        Path dependency = packageDirectory.resolve("Dependency.class");
        try (var writer = new InMemoryJavaCompiler(); var compiler = new InMemoryJavaCompiler()) {
            for (int value : new int[] {1, 2}) {
                Files.write(dependency, writer.compile(
                        "package fixture; public class Dependency { public static final int VALUE = " + value + "; }",
                        "fixture.Dependency", "").get("fixture.Dependency"));
                assertEquals(value, compileValue(compiler, directory.toString()));
            }
        }
    }

    @Test
    void closingReleasesArchivesAndRejectsFurtherCompiles() throws Exception {
        Path archive = dependency("dependency.jar", 1);
        var compiler = new InMemoryJavaCompiler();
        try {
            assertEquals(1, compileValue(compiler, archive.toString()));
        } finally {
            compiler.close();
        }
        Files.delete(archive);
        compiler.close();
        assertTrue(assertThrows(InMemoryCompilationException.class,
                () -> compiler.compile("public class AfterClose {}", "AfterClose", ""))
                .getMessage().contains("closed"));
    }

    private Path dependency(String name, int value) throws Exception {
        Path path = this.temporaryDirectory.resolve(name);
        try (var compiler = new InMemoryJavaCompiler(); var jar = new ZipOutputStream(Files.newOutputStream(path))) {
            byte[] bytes = compiler.compile("package fixture; public class Dependency { public static final int VALUE = "
                    + value + "; }", "fixture.Dependency", "").get("fixture.Dependency");
            jar.putNextEntry(new ZipEntry("fixture/Dependency.class"));
            jar.write(bytes);
        }
        return path;
    }

    private static int compileValue(InMemoryJavaCompiler compiler, String classpath) throws Exception {
        Map<String, byte[]> bytes = compiler.compile("""
                public class ReadValue {
                    public int value() { return fixture.Dependency.VALUE; }
                }
                """, "ReadValue", classpath);
        Class<?> result = new ScriptClassLoader(InMemoryJavaCompilerSessionTest.class.getClassLoader(), bytes)
                .loadClass("ReadValue");
        return (int) result.getMethod("value").invoke(result.getConstructor().newInstance());
    }
}
