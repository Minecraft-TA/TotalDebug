package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Lookups over one captured {@link PackCatalog}. Registered content belongs to the mod whose id is its registry
 * namespace; content in a namespace without such a mod is kept under that namespace.
 */
public final class CatalogIndex {
    /** A registered block, item or entity type with the fields every browser shows. */
    public record Entry(SubjectRef.DefinitionKind kind, String id, String name, String iconItem) {
        public String namespace() {
            return this.id.substring(0, this.id.indexOf(':'));
        }

        public String title() {
            return this.name.isEmpty() ? this.id : this.name;
        }

        public SubjectRef.Definition subject() {
            return new SubjectRef.Definition(this.kind, this.id);
        }
    }

    public record ItemIcon(String model, Map<Integer, Integer> tints) {
    }

    private final PackCatalog catalog;
    private final List<Path> vanillaResources;
    private final Map<String, PackCatalog.Mod> mods = new LinkedHashMap<>();
    private final Map<String, PackCatalog.BlockEntry> blocks = new HashMap<>();
    private final Map<String, PackCatalog.ItemEntry> items = new HashMap<>();
    private final Map<String, PackCatalog.EntityTypeEntry> entityTypes = new HashMap<>();
    private final Map<String, Map<SubjectRef.DefinitionKind, List<Entry>>> entriesByNamespace = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> otherNamespaces;

    public CatalogIndex(PackCatalog catalog) {
        this(catalog, List.of());
    }

    CatalogIndex(PackCatalog catalog, List<Path> vanillaResources) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.vanillaResources = List.copyOf(vanillaResources);
        catalog.mods().stream()
                .sorted(Comparator.comparing(PackCatalog.Mod::title, String.CASE_INSENSITIVE_ORDER))
                .forEach(mod -> this.mods.put(mod.id(), mod));
        index(catalog.blocks(), this.blocks, PackCatalog.BlockEntry::id, CatalogIndex::entry);
        index(catalog.items(), this.items, PackCatalog.ItemEntry::id, CatalogIndex::entry);
        index(catalog.entityTypes(), this.entityTypes, PackCatalog.EntityTypeEntry::id, CatalogIndex::entry);
        for (Map<SubjectRef.DefinitionKind, List<Entry>> byKind : this.entriesByNamespace.values()) {
            for (List<Entry> list : byKind.values()) {
                list.sort(Comparator.comparing(Entry::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
            }
        }
        TreeSet<String> namespaces = new TreeSet<>(this.entriesByNamespace.keySet());
        namespaces.removeAll(this.mods.keySet());
        this.otherNamespaces = List.copyOf(namespaces);
    }

    private <T> void index(List<T> values, Map<String, T> byId, Function<T, String> id, Function<T, Entry> entry) {
        for (T value : values) {
            byId.put(id.apply(value), value);
            // Keep block items addressable (and usable as icons), but browse them only under Blocks.
            if (value instanceof PackCatalog.ItemEntry item && !item.block().isEmpty()
                    && this.blocks.containsKey(item.block())) continue;
            Entry created = entry.apply(value);
            this.entries.add(created);
            this.entriesByNamespace.computeIfAbsent(created.namespace(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(created.kind(), ignored -> new ArrayList<>())
                    .add(created);
        }
    }

    private static Entry entry(PackCatalog.BlockEntry block) {
        return new Entry(SubjectRef.DefinitionKind.BLOCK, block.id(), block.name(), block.item());
    }

    private static Entry entry(PackCatalog.ItemEntry item) {
        return new Entry(SubjectRef.DefinitionKind.ITEM, item.id(), item.name(), item.id());
    }

    private static Entry entry(PackCatalog.EntityTypeEntry type) {
        return new Entry(SubjectRef.DefinitionKind.ENTITY_TYPE, type.id(), type.name(), type.spawnEgg());
    }

    public PackCatalog catalog() {
        return this.catalog;
    }

    /** Installed mods ordered by name. */
    public List<PackCatalog.Mod> mods() {
        return List.copyOf(this.mods.values());
    }

    public Optional<PackCatalog.Mod> mod(String modId) {
        return Optional.ofNullable(this.mods.get(modId));
    }

    /** Local files backing a mod, including vanilla's separately packaged assets. */
    public List<Path> resourceFiles(String modId) {
        List<Path> files = new ArrayList<>();
        mod(modId).ifPresent(mod -> {
            if ("file".equalsIgnoreCase(mod.file().getScheme())) {
                Path file = Path.of(mod.file());
                if (Files.exists(file)) files.add(file);
            }
        });
        if (modId.equals("minecraft")) {
            for (Path file : this.vanillaResources) if (!files.contains(file)) files.add(file);
        }
        return List.copyOf(files);
    }

    /** Namespaces with registered content but no mod of the same id. */
    public List<String> otherNamespaces() {
        return this.otherNamespaces;
    }

    /** Display name of the mod owning a namespace, or the namespace itself. */
    public String ownerName(String namespace) {
        PackCatalog.Mod mod = this.mods.get(namespace);
        return mod == null ? namespace : mod.title();
    }

    /** The content a mod or namespace registered, ordered by name. */
    public List<Entry> entries(String namespace, SubjectRef.DefinitionKind kind) {
        return this.entriesByNamespace.getOrDefault(namespace, Map.of()).getOrDefault(kind, List.of());
    }

    /** All registered content, in registry order. */
    public List<Entry> entries() {
        return this.entries;
    }

    public Optional<Entry> entry(SubjectRef.Definition subject) {
        return switch (subject.kind()) {
            case BLOCK -> block(subject.id()).map(CatalogIndex::entry);
            case ITEM -> item(subject.id()).map(CatalogIndex::entry);
            case ENTITY_TYPE -> entityType(subject.id()).map(CatalogIndex::entry);
        };
    }

    public Optional<PackCatalog.BlockEntry> block(String id) {
        return Optional.ofNullable(this.blocks.get(id));
    }

    public Optional<PackCatalog.ItemEntry> item(String id) {
        return Optional.ofNullable(this.items.get(id));
    }

    public Optional<PackCatalog.EntityTypeEntry> entityType(String id) {
        return Optional.ofNullable(this.entityTypes.get(id));
    }

    /** The model and tints that draw an item's icon; empty when the id is not a captured item. */
    public Optional<ItemIcon> itemIcon(String itemId) {
        return item(itemId).map(item -> new ItemIcon(
                item.model().isEmpty() ? ItemIconService.itemModel(item.id()) : item.model(), item.tints()));
    }
}
