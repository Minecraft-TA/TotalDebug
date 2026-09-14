package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.LocalSourceGuard;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.ModuleKind;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.RuntimeModule;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.PreparedInput;
import com.github.tth05.jindex.IndexSource;
import org.objectweb.asm.ClassReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.CancellationException;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Physical mod archives, independent of Minecraft and of index readiness. */
public final class LocalModSources {
    record Scan(List<Source> sources, List<Source> readable, LocalSourceGuard guard, String detail) {
        Scan { sources = List.copyOf(sources); readable = List.copyOf(readable); }
        IndexIdentity identity() { return guard.identity(); }
    }

    record Prepared(List<PreparedInput> inputs, String detail) {
        Prepared { inputs = List.copyOf(inputs); }
    }

    private LocalModSources() { }

    public static List<Source> discover(Path gameDirectory) throws IOException {
        Path mods = gameDirectory.resolve("mods");
        if (!Files.isDirectory(mods)) return List.of();
        List<Path> archives;
        try (var paths = Files.list(mods)) {
            archives = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList();
        }
        var result = new ArrayList<Source>();
        for (Path archive : archives) {
            String name = archive.getFileName().toString();
            result.add(new Source(result.size(), archive, archive.toUri().toString(), new RuntimeModule(name, name, ModuleKind.MOD)));
        }
        return List.copyOf(result);
    }

    static Scan scan(Path gameDirectory, Runnable checkpoint) throws IOException {
        var sources = discover(gameDirectory);
        var fingerprints = new LinkedHashMap<Path, LocalSourceGuard.Fingerprint>();
        var readable = new ArrayList<Source>();
        var problems = new ArrayList<String>();
        for (var source : sources) {
            checkpoint.run();
            try {
                fingerprints.put(source.path(), LocalSourceGuard.capture(source.path()));
                try (var archive = new JarFile(source.path().toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                    archive.size();
                }
                readable.add(source);
            } catch (IOException failure) {
                problems.add(source.path().getFileName() + ": " + failure.getMessage());
            }
        }
        String detail = problems.isEmpty() ? "" : "Some archives could not be indexed: " + String.join("; ", problems) + ". ";
        return new Scan(sources, readable, new LocalSourceGuard(fingerprints), detail);
    }

    static Prepared prepare(Scan scan, Runnable checkpoint) throws IOException {
        var inputs = new ArrayList<PreparedInput>();
        var seen = new LinkedHashSet<String>();
        var detail = new StringBuilder(scan.detail());
        int duplicates = 0;
        for (var source : scan.readable()) {
            checkpoint.run();
            var archiveInputs = new LinkedHashMap<String, PreparedInput>();
            try (var archive = new JarFile(source.path().toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                for (var entry : archive.versionedStream()
                        .filter(item -> !item.isDirectory() && item.getName().endsWith(".class") && !item.getName().equals("module-info.class"))
                        .filter(item -> !item.getName().endsWith("/module-info.class"))
                        .filter(item -> !item.getName().startsWith("META-INF/")).toList()) {
                    checkpoint.run();
                    byte[] bytes;
                    try (var input = archive.getInputStream(entry)) { bytes = input.readAllBytes(); }
                    String name = new ClassReader(bytes).getClassName();
                    archiveInputs.putIfAbsent(name, new PreparedInput(IndexSource.classFile(source.sourceId(), bytes), source));
                }
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (IOException | RuntimeException failure) {
                detail.append("Could not index ").append(source.path().getFileName()).append(": ").append(failure.getMessage()).append(". ");
                continue;
            }
            for (var entry : archiveInputs.entrySet()) {
                if (seen.add(entry.getKey())) inputs.add(entry.getValue());
                else duplicates++;
            }
        }
        if (duplicates > 0) detail.append(duplicates).append(" duplicate classes use the first archive in filename order. ");
        scan.guard().checkAll();
        return new Prepared(inputs, detail.toString());
    }
}
