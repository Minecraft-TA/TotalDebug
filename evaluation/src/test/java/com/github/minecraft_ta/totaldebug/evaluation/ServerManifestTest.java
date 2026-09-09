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
            assertEquals(ClassDeclarations.fingerprint(original), ClassDeclarations.fingerprint(body));
            for (String changed : List.of(source.replace("LIMIT = 7", "LIMIT = 8"),
                    source.replace("int value()", "long value()"),
                    source.replace("class Api", "class Api implements java.io.Serializable"),
                    source.replace("public int value", "private int value"))) {
                byte[] bytes = compiler.compile(changed, "Api", "").get("Api");
                assertNotEquals(ClassDeclarations.fingerprint(original), ClassDeclarations.fingerprint(bytes));
            }
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
    void baselineAndNamesDoNotReadClassBodiesAndRequestedDetailsAreCachedOnce() throws Exception {
        Path broken = jar("broken.jar", Map.of("Broken", new byte[]{1, 2, 3}));
        var brokenCatalog = new ServerManifest.Catalog(List.of(broken));
        assertEquals(1, ServerManifest.decode(brokenCatalog.baseline()).sources().size());
        assertEquals(Map.of("Broken", ""), ServerManifest.readClasses(broken, false));
        assertThrows(RuntimeException.class, () -> brokenCatalog.details(0));
        try (var compiler = new InMemoryJavaCompiler()) {
            Path valid = jar("valid.jar", compiler.compile("public class Api {}", "Api", ""));
            var catalog = new ServerManifest.Catalog(List.of(valid));
            byte[] details = catalog.details(0);
            assertTrue(ServerManifest.decodeDetails(details).containsKey("Api"));
            Files.delete(valid);
            assertSame(details, catalog.details(0), "Second player must reuse the cached encoded source");
            var cached = catalog;
            assertThrows(IOException.class, () -> cached.details(1));
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
            var details = ServerManifest.readClasses(archive, true);
            assertEquals(Map.of("Api", ClassDeclarations.fingerprint(release)), details);
            assertEquals(Map.of("Api", ""), ServerManifest.readClasses(archive, false));
            assertEquals(details, ServerManifest.decodeDetails(ServerManifest.encodeDetails(details)));
            Path classes = Files.createDirectory(directory.resolve("classes"));
            Files.write(classes.resolve("Api.class"), release);
            assertEquals(details, ServerManifest.readClasses(classes, true));
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
