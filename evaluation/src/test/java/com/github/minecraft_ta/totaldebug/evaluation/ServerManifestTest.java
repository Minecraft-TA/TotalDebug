package com.github.minecraft_ta.totaldebug.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ServerManifestTest {
    @TempDir Path directory;

    @Test
    void bodiesMayDifferButConstantsSignaturesAndHierarchyMustMatch() throws Exception {
        try (var compiler = new InMemoryJavaCompiler()) {
            String source = "public class Api { public static final int LIMIT = 7; public int value() { return 1; } }";
            byte[] original = compiler.compile(source, "Api", "").get("Api");
            var manifest = ServerManifest.scan(List.of(jar("server.jar", Map.of("Api", original))));
            byte[] body = compiler.compile(source.replace("return 1", "return 2000"), "Api", "").get("Api");
            manifest.requireCompatible("Api", "", body);
            for (String changed : List.of(source.replace("LIMIT = 7", "LIMIT = 8"),
                    source.replace("int value()", "long value()"),
                    source.replace("class Api", "class Api implements java.io.Serializable"),
                    source.replace("public int value", "private int value"))) {
                byte[] bytes = compiler.compile(changed, "Api", "").get("Api");
                IOException failure = assertThrows(IOException.class, () -> manifest.requireCompatible("Api", "", bytes));
                assertTrue(failure.getMessage().contains("declarations differ for Api"));
            }
            assertThrows(IOException.class, () -> manifest.requireCompatible("Missing", "", original));
            assertEquals(manifest, ServerManifest.decode(manifest.encode()));
        }
    }

    @Test
    void genericSignaturesAndInheritedConstantsAreDeclarations() throws Exception {
        try (var compiler = new InMemoryJavaCompiler()) {
            String source = "public class Api { public java.util.List<String> values; }";
            byte[] original = compiler.compile(source, "Api", "").get("Api");
            byte[] changed = compiler.compile(source.replace("String", "Integer"), "Api", "").get("Api");
            assertNotEquals(ClassDeclarations.fingerprint(original), ClassDeclarations.fingerprint(changed));
        }
    }

    @Test
    void serverSourceOrderWinsEvenWhenLaterArchiveMatchesClientExactly() throws Exception {
        try (var compiler = new InMemoryJavaCompiler()) {
            byte[] client = compiler.compile("public class Api { public int value; }", "Api", "").get("Api");
            byte[] server = compiler.compile("public class Api { public long value; }", "Api", "").get("Api");
            Path first = jar("first.jar", Map.of("Api", server));
            Path second = jar("second.jar", Map.of("Api", client));
            var manifest = ServerManifest.scan(List.of(first, second));
            assertThrows(IOException.class, () -> manifest.requireCompatible("Api", ClassDeclarations.archiveFingerprint(second), client));
            manifest.requireCompatible("Api", ClassDeclarations.archiveFingerprint(first), server);
        }
    }

    @Test
    void directoriesAndMultiReleaseArchivesUseTheJava21View() throws Exception {
        try (var compiler = new InMemoryJavaCompiler()) {
            byte[] base = compiler.compile("public class Api { public int value; }", "Api", "").get("Api");
            byte[] release = compiler.compile("public class Api { public long value; }", "Api", "").get("Api");
            var attributes = new Manifest();
            attributes.getMainAttributes().putValue("Manifest-Version", "1.0");
            attributes.getMainAttributes().putValue("Multi-Release", "true");
            Path archive = directory.resolve("multi.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(archive), attributes)) {
                for (var entry : Map.of("Api.class", base, "META-INF/versions/21/Api.class", release,
                        "META-INF/versions/22/Api.class", base).entrySet()) {
                    output.putNextEntry(new JarEntry(entry.getKey()));
                    output.write(entry.getValue());
                    output.closeEntry();
                }
            }
            var manifest = ServerManifest.scan(List.of(archive));
            manifest.requireCompatible("Api", "", release);
            assertThrows(IOException.class, () -> manifest.requireCompatible("Api", "", base));
            Path classes = Files.createDirectory(directory.resolve("classes"));
            Files.write(classes.resolve("Api.class"), release);
            assertEquals(manifest.classes(), ServerManifest.scan(List.of(classes)).classes());
        }
    }

    @Test
    void malformedManifestFailsWithoutBecomingAnEmptyEnvironment() {
        assertThrows(IOException.class, () -> ServerManifest.decode(new byte[]{1, 2, 3}));
    }

    private Path jar(String name, Map<String, byte[]> classes) throws IOException {
        Path path = directory.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
