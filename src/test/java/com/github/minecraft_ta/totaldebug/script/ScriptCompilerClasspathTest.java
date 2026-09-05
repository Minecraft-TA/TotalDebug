package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;

import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceInventory;
import com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptCompilerClasspathTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void joinsOnlyExistingRuntimeSourcesWithThePlatformSeparator() throws Exception {
        Path classes = Files.createDirectory(this.temporaryDirectory.resolve("classes"));
        Path libraries = Files.createDirectory(this.temporaryDirectory.resolve("libraries"));

        ScriptCompilerClasspath classpath = ScriptCompilerClasspath.fromSources(List.of(libraries, classes));

        assertEquals(List.of(libraries, classes), classpath.sources());
        assertEquals(libraries + File.pathSeparator + classes, classpath.argument());
        assertTrue(classpath.sources().stream().allMatch(Files::exists));
    }

    @Test
    void compilesAnOverloadWhoseSiblingUsesAVirtualRuntimeDependency() throws Exception {
        InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();
        Path dependency = jar("dependency.jar", compiler.compile(
                "package fixture; public interface QuadView {}", "fixture.QuadView", ""
        ));
        Path owner = jar("owner.jar", compiler.compile("""
                package fixture;
                public class Orientation {
                    public static int choose(float[] brightness, int[] light) { return 1; }
                    public static int choose(float[] brightness, QuadView quad) { return 2; }
                }
                """, "fixture.Orientation", dependency.toString()));

        try (var virtual = FileSystems.newFileSystem(dependency)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ScriptCompilerClasspath.fromSources(List.of(owner, virtual.getPath("/"))));
            var prepared = RuntimeSourceMaterializer.prepare(
                    List.of(new RuntimeSourceInventory.Source(owner, "owner"),
                            new RuntimeSourceInventory.Source(virtual.getPath("/"), "dependency")),
                    this.temporaryDirectory.resolve("cache")
            );
            ScriptCompilerClasspath classpath = ScriptCompilerClasspath.fromSources(
                    prepared.paths()
            );
            Map<String, byte[]> result = compiler.compile("""
                    public class Probe {
                        public int run() {
                            return fixture.Orientation.choose(new float[4], new int[4]);
                        }
                    }
                    """, "Probe", classpath.argument());
            assertTrue(result.containsKey("Probe"));
        }
    }

    private Path jar(String name, Map<String, byte[]> classes) throws Exception {
        Path path = this.temporaryDirectory.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey().replace('.', '/') + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
