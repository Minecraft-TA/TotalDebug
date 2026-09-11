package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.ClassDeclarations;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Temporary handshake work. Only the resulting unsupported names survive comparison. */
final class ServerCompatibility {
    private final ServerManifest server;
    private final Map<Integer, Source> localSources = new HashMap<>();
    private final Map<Integer, String> localHashes = new HashMap<>();
    private final Map<Path, Map<String, String>> localNames = new HashMap<>();
    private final Map<Integer, Path> matchingSources = new HashMap<>();
    private final Map<Integer, Map<String, String>> details = new LinkedHashMap<>();

    ServerCompatibility(ServerManifest server, List<Source> sources) throws IOException {
        this.server = server;
        var byHash = new HashMap<String, Path>();
        for (Source source : sources) {
            if ("jrt:/".equals(source.logicalUri())) continue;
            localSources.put(source.sourceId(), source);
            String hash = Files.isDirectory(source.path()) ? "" : ClassDeclarations.archiveFingerprint(source.path());
            localHashes.put(source.sourceId(), hash);
            if (!hash.isEmpty()) byHash.putIfAbsent(hash, source.path());
            localNames.put(source.path(), ServerManifest.readClasses(source.path(), false));
        }
        for (int i = 0; i < server.sources().size(); i++) {
            Path matching = byHash.get(server.sources().get(i).archiveHash());
            if (matching == null) details.put(i, null);
            else matchingSources.put(i, matching);
        }
    }

    int nextSource() {
        for (var entry : details.entrySet()) if (entry.getValue() == null) return entry.getKey();
        return -1;
    }

    void accept(int source, Map<String, String> classes) throws IOException {
        if (source != nextSource()) throw new IOException("Unexpected server source details");
        details.put(source, classes);
    }

    /** The caller holds the compiler lock so the borrowed native index cannot close. */
    Set<String> finish(ReadySnapshot snapshot) throws IOException {
        if (nextSource() != -1) throw new IOException("Server class comparison is incomplete");
        var winners = new HashMap<String, Integer>();
        for (int i = 0; i < server.sources().size(); i++) {
            Path matching = matchingSources.get(i);
            var names = matching == null ? details.get(i).keySet() : localNames.get(matching).keySet();
            for (String name : names) winners.putIfAbsent(name, i);
        }
        var declarations = new HashMap<Path, Map<String, String>>();
        var unsupported = new HashSet<String>();
        var visited = new HashSet<String>();
        for (var names : localNames.values()) {
            for (String name : names.keySet()) {
                if (!visited.add(name)) continue;
                var indexed = snapshot.index().findClass(name);
                if (indexed == null) continue;
                Source local = localSources.get(indexed.getSourceId());
                if (local == null) continue; // javac supplies JDK classes through --release.
                Integer winner = winners.get(name);
                if (winner == null) {
                    unsupported.add(name);
                    continue;
                }
                String serverHash = server.sources().get(winner).archiveHash();
                if (!serverHash.isEmpty() && serverHash.equals(localHashes.get(local.sourceId()))) continue;
                Path matching = matchingSources.get(winner);
                String expected = matching == null ? details.get(winner).get(name)
                        : declarations(declarations, matching).get(name);
                if (!expected.equals(declarations(declarations, local.path()).get(name))) unsupported.add(name);
            }
        }
        return Set.copyOf(unsupported);
    }

    private static Map<String, String> declarations(Map<Path, Map<String, String>> cache, Path path) throws IOException {
        var classes = cache.get(path);
        if (classes == null) {
            classes = ServerManifest.readClasses(path, true);
            cache.put(path, classes);
        }
        return classes;
    }
}
