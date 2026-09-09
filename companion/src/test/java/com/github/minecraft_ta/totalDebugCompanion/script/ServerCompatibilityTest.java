package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeTestSources.librarySource;
import static org.junit.jupiter.api.Assertions.*;

class ServerCompatibilityTest {
    @TempDir Path directory;

    @Test
    void matchingArchivesNeedNoDetailsAndDifferentPlayersOwnDifferentResults() throws Exception {
        Path api = jar("api.jar", "public class Api { public int value() { return 1; } }");
        Path extra = jar("extra.jar", "public class Extra {}");
        var baseline = ServerManifest.scan(List.of(api));
        try (var first = snapshot(List.of(api)); var second = snapshot(List.of(api, extra))) {
            var alice = new ServerCompatibility(baseline, first.sources());
            var bob = new ServerCompatibility(baseline, second.sources());
            assertEquals(-1, alice.nextSource());
            assertEquals(-1, bob.nextSource());
            assertEquals(Set.of(), alice.finish(first));
            assertEquals(Set.of("Extra"), bob.finish(second));
        }
    }

    @Test
    void reversedIdenticalArchivesCompareTheActualWinningDefinitions() throws Exception {
        Path first = jar("first.jar", "public class Api { public int value; }");
        Path second = jar("second.jar", "public class Api { public long value; }");
        try (var local = snapshot(List.of(first, second))) {
            assertEquals(0, local.index().findClass("Api").getSourceId());
            var comparison = new ServerCompatibility(ServerManifest.scan(List.of(second, first)), local.sources());
            assertEquals(-1, comparison.nextSource(), "Both archives are already local");
            assertEquals(Set.of("Api"), comparison.finish(local));
        }
    }

    @Test
    void changedEarlierSourceCannotHideBehindAnIdenticalLaterArchive() throws Exception {
        Path local = jar("local.jar", "public class Api { public int value; }");
        Path changed = jar("changed.jar", "public class Api { public long value; }");
        try (var snapshot = snapshot(List.of(local))) {
            var comparison = new ServerCompatibility(ServerManifest.scan(List.of(changed, local)), snapshot.sources());
            assertEquals(0, comparison.nextSource());
            assertThrows(Exception.class, () -> comparison.finish(snapshot));
            comparison.accept(0, ServerManifest.readClasses(changed, true));
            assertEquals(Set.of("Api"), comparison.finish(snapshot));
        }
    }

    @Test
    void methodBodiesRemainUsableAndUnmatchedDirectoriesUseDetails() throws Exception {
        Path local = jar("local.jar", "public class Api { public int value() { return 1; } }");
        Path server = Files.createDirectory(directory.resolve("server"));
        try (var compiler = new InMemoryJavaCompiler(); var snapshot = snapshot(List.of(local))) {
            Files.write(server.resolve("Api.class"), compiler.compile(
                    "public class Api { public int value() { return 2000; } }", "Api", "").get("Api"));
            var comparison = new ServerCompatibility(ServerManifest.scan(List.of(server)), snapshot.sources());
            assertEquals(0, comparison.nextSource());
            comparison.accept(0, ServerManifest.readClasses(server, true));
            assertEquals(Set.of(), comparison.finish(snapshot));
        }
    }

    private ReadySnapshot snapshot(List<Path> paths) {
        var sources = IntStream.range(0, paths.size()).mapToObj(i -> librarySource(i, paths.get(i))).toList();
        var index = ClassIndex.fromSources(sources.stream().map(source -> IndexSource.archive(source.sourceId(), source.path().toString())).toList());
        return new ReadySnapshot("inventory", "signature", directory.resolve("index.jindex"), sources, index);
    }

    private Path jar(String filename, String source) throws Exception {
        String name = source.substring("public class ".length()).split(" ")[0];
        Path path = directory.resolve(filename);
        try (var compiler = new InMemoryJavaCompiler(); var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : compiler.compile(source, name, "").entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
