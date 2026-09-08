package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.GameCatalog;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Caches only the manifest. Archive handles belong to short-lived inspection jobs. */
public final class GameCatalogService {
    public static final GameCatalogService INSTANCE = new GameCatalogService();
    private CatalogSnapshot cached;
    private Path inventoryPath;
    private java.nio.file.attribute.FileTime inventoryModified;
    private long inventorySize;
    private String inventoryId;

    public synchronized CatalogSnapshot load(InstancePaths paths) throws IOException {
        Path archive = paths.gameCatalog();
        if (!Files.isRegularFile(archive))
            throw new IOException("No item/block capture yet. Connect the updated game to Companion and wait for capture to finish.");
        var attributes = Files.readAttributes(archive, BasicFileAttributes.class);
        if (cached == null || !cached.archive().equals(archive) || !cached.matches(attributes)) {
            GameCatalog catalog = GameCatalog.read(archive);
            cached = new CatalogSnapshot(archive, catalog, attributes.size(), attributes.lastModifiedTime());
            cached.checkCurrent();
        }
        var inventoryAttributes = Files.readAttributes(paths.inventory(), BasicFileAttributes.class);
        if (!paths.inventory().equals(inventoryPath) || inventoryAttributes.size() != inventorySize
                || !inventoryAttributes.lastModifiedTime().equals(inventoryModified)) {
            inventoryId = RuntimeInventory.read(paths.inventory()).id();
            inventoryPath = paths.inventory();
            inventoryModified = inventoryAttributes.lastModifiedTime();
            inventorySize = inventoryAttributes.size();
        }
        if (!cached.catalog().inventoryId().equals(inventoryId))
            throw new IOException("The item/block capture belongs to a different runtime. Use Browse > Refresh game data while Minecraft is connected.");
        return cached;
    }

    /** Namespace membership is separate from implementation-class ownership. A shared module can contain several mods. */
    public static List<GameCatalog.Entry> search(GameCatalog catalog, String query, GameCatalog.Kind kind,
                                                  Set<String> moduleIds, int limit) {
        String folded = query.strip().toLowerCase(Locale.ROOT);
        Set<String> namespaces = moduleIds == null ? null : moduleIds.stream()
                .flatMap(module -> java.util.Arrays.stream(module.split("\\+")))
                .collect(java.util.stream.Collectors.toSet());
        return catalog.entries().stream()
                .filter(entry -> kind == null || entry.kind() == kind)
                .filter(entry -> namespaces == null || namespaces.contains(entry.namespace()))
                .filter(entry -> entry.id().toLowerCase(Locale.ROOT).contains(folded)
                        || entry.name().toLowerCase(Locale.ROOT).contains(folded)
                        || entry.modName().toLowerCase(Locale.ROOT).contains(folded))
                .sorted(java.util.Comparator.comparingInt((GameCatalog.Entry entry) ->
                        entry.id().equalsIgnoreCase(query) || entry.name().equalsIgnoreCase(query) ? 0 : 1)
                        .thenComparing(GameCatalog.Entry::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(GameCatalog.Entry::id).thenComparing(GameCatalog.Entry::kind))
                .limit(limit).toList();
    }
}
