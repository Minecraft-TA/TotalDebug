package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a mod page shows about its subject. A captured mod has its metadata and content; a namespace without a mod has
 * only content; a runtime module seen before the first capture has only its files.
 */
public record ModSummary(String id, String title, String version, PackCatalog.Mod mod, String moduleId, List<Path> files) {
    public ModSummary {
        files = List.copyOf(files);
    }

    /** True when this page describes a captured mod or namespace, so its registered content is known. */
    public boolean captured() {
        return this.mod != null || this.moduleId.isEmpty();
    }

    public static Optional<ModSummary> resolve(String id, CatalogIndex index, RuntimeSourceCatalog sources) {
        if (index != null) {
            Optional<PackCatalog.Mod> mod = index.mod(id);
            if (mod.isPresent()) {
                PackCatalog.Mod found = mod.get();
                return Optional.of(new ModSummary(found.id(), found.title(), found.version(), found, found.module(),
                        index.resourceFiles(found.id())));
            }
            if (index.otherNamespaces().contains(id)) {
                return Optional.of(new ModSummary(id, id, "", null, "", List.of()));
            }
        }
        for (RuntimeInventory.RuntimeModule module : sources.modules()) {
            if (module.id().equals(id)) {
                List<URI> uris = new ArrayList<>();
                for (RuntimeSnapshotBytecodeSource.Source source : sources.sourcesForModule(id)) {
                    uris.add(URI.create(source.logicalUri()));
                }
                return Optional.of(new ModSummary(id, module.displayName(), "", null, id, existing(uris)));
            }
        }
        return Optional.empty();
    }

    /** The original files of a mod; only local files can be listed. */
    private static List<Path> existing(List<URI> uris) {
        List<Path> files = new ArrayList<>();
        for (URI uri : uris) {
            if (!"file".equalsIgnoreCase(uri.getScheme())) continue;
            Path path = Path.of(uri);
            if (Files.exists(path) && !files.contains(path)) files.add(path);
        }
        return files;
    }
}
