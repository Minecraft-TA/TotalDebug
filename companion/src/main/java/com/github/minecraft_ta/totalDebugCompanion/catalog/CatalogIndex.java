package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Lookups over one captured {@link PackCatalog}. Registered content belongs to the mod whose id is its registry
 * namespace; content in a namespace without such a mod is kept under that namespace. Every captured registry is
 * indexed alike, except that an item placing a captured block is browsed only as that block.
 */
public final class CatalogIndex {
    /**
     * A registered entry with the fields every browser shows: {@code registry} is the registry it belongs to, such as
     * {@code minecraft:block}, and {@code iconItem} the item that draws it, or empty.
     */
    public record Entry(String registry, String id, String name, String iconItem) {
        public String namespace() {
            return this.id.substring(0, this.id.indexOf(':'));
        }

        public String title() {
            return this.name.isEmpty() ? this.id : this.name;
        }

        public SubjectRef.Definition subject() {
            return new SubjectRef.Definition(this.registry, this.id);
        }
    }

    public record ItemIcon(String model, Map<Integer, Integer> tints) {
    }

    private final PackCatalog catalog;
    private final List<Path> vanillaResources;
    private final Map<String, PackCatalog.Mod> mods = new LinkedHashMap<>();
    /** Every captured entry, by registry and id, including items browsed as their block. */
    private final Map<String, Map<String, PackCatalog.RegistryEntry>> definitions = new HashMap<>();
    /** The browsed entries by registry, in the catalog's registry order. */
    private final Map<String, List<Entry>> byRegistry;
    private final Map<String, Map<String, List<Entry>>> byNamespace;
    private final List<Entry> entries;
    private final List<String> otherNamespaces;
    private final Map<String, List<PackCatalog.KeyBinding>> keyBindingsByMod = new HashMap<>();

    public CatalogIndex(PackCatalog catalog) {
        this(catalog, List.of());
    }

    CatalogIndex(PackCatalog catalog, List<Path> vanillaResources) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.vanillaResources = List.copyOf(vanillaResources);
        catalog.mods().stream()
                .sorted(Comparator.comparing(PackCatalog.Mod::title, String.CASE_INSENSITIVE_ORDER))
                .forEach(mod -> this.mods.put(mod.id(), mod));
        for (PackCatalog.Registry registry : catalog.registries()) {
            Map<String, PackCatalog.RegistryEntry> byId = new HashMap<>();
            for (PackCatalog.RegistryEntry entry : registry.entries()) byId.put(entry.id(), entry);
            this.definitions.put(registry.id(), byId);
        }
        List<Entry> entries = new ArrayList<>();
        Map<String, List<Entry>> byRegistry = new LinkedHashMap<>();
        Map<String, Map<String, List<Entry>>> byNamespace = new HashMap<>();
        for (PackCatalog.Registry registry : catalog.registries()) {
            for (PackCatalog.RegistryEntry captured : registry.entries()) {
                if (placesBlock(registry.id(), captured)) continue;
                Entry entry = new Entry(registry.id(), captured.id(), captured.name(), captured.icon());
                entries.add(entry);
                byRegistry.computeIfAbsent(registry.id(), ignored -> new ArrayList<>()).add(entry);
                byNamespace.computeIfAbsent(entry.namespace(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(registry.id(), ignored -> new ArrayList<>())
                        .add(entry);
            }
        }
        Comparator<Entry> byTitle = Comparator.comparing(Entry::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id);
        Map<String, Map<String, List<Entry>>> sortedByNamespace = new HashMap<>();
        byNamespace.forEach((namespace, content) -> {
            Map<String, List<Entry>> sorted = new LinkedHashMap<>();
            content.forEach((registry, list) -> sorted.put(registry, list.stream().sorted(byTitle).toList()));
            sortedByNamespace.put(namespace, Collections.unmodifiableMap(sorted));
        });
        this.entries = List.copyOf(entries);
        this.byRegistry = frozen(byRegistry);
        this.byNamespace = Map.copyOf(sortedByNamespace);
        TreeSet<String> namespaces = new TreeSet<>(this.byNamespace.keySet());
        namespaces.removeAll(this.mods.keySet());
        this.otherNamespaces = List.copyOf(namespaces);
        for (PackCatalog.KeyBinding binding : catalog.keyBindings()) {
            this.keyBindingsByMod.computeIfAbsent(keyBindingOwner(binding), ignored -> new ArrayList<>()).add(binding);
        }
    }

    /** An unchangeable copy that keeps the order of {@code content}. */
    private static Map<String, List<Entry>> frozen(Map<String, List<Entry>> content) {
        Map<String, List<Entry>> copy = new LinkedHashMap<>();
        content.forEach((registry, list) -> copy.put(registry, List.copyOf(list)));
        return Collections.unmodifiableMap(copy);
    }

    /** Whether an item places a captured block; it stays addressable, and usable as an icon, but is browsed as the block. */
    private boolean placesBlock(String registry, PackCatalog.RegistryEntry entry) {
        if (!registry.equals(RegistryIds.ITEM)) return false;
        String block = entry.link("block");
        return !block.isEmpty() && this.definitions.getOrDefault(RegistryIds.BLOCK, Map.of()).containsKey(block);
    }

    /**
     * The mod a key binding belongs to: the first installed mod its name names, such as {@code ftbchunks} for
     * {@code key.ftbchunks.map}, otherwise the mod that registered it. Libraries like Architectury register bindings
     * for other mods, and some bindings are added outside NeoForge's key registration and have no registering mod.
     */
    public String keyBindingOwner(PackCatalog.KeyBinding binding) {
        for (String part : binding.name().split("\\.")) {
            if (this.mods.containsKey(part)) return part;
        }
        return binding.modId();
    }

    /** The key bindings that belong to a mod, in registration order. */
    public List<PackCatalog.KeyBinding> keyBindings(String modId) {
        return List.copyOf(this.keyBindingsByMod.getOrDefault(modId, List.of()));
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

    /** The content a mod or namespace registered, by registry in catalog order, each ordered by name. */
    public Map<String, List<Entry>> content(String namespace) {
        return this.byNamespace.getOrDefault(namespace, Map.of());
    }

    /** All browsed content by registry, in catalog order, each in registry order. */
    public Map<String, List<Entry>> content() {
        return this.byRegistry;
    }

    /** All browsed content, in catalog and registry order. */
    public List<Entry> entries() {
        return this.entries;
    }

    public Optional<Entry> entry(SubjectRef.Definition subject) {
        return definition(subject).map(entry -> new Entry(subject.registry(), entry.id(), entry.name(), entry.icon()));
    }

    /** What the catalog recorded about a definition; empty when its registry or the entry was not captured. */
    public Optional<PackCatalog.RegistryEntry> definition(SubjectRef.Definition subject) {
        return Optional.ofNullable(this.definitions.getOrDefault(subject.registry(), Map.of()).get(subject.id()));
    }

    /** The model and tints that draw an item's icon; empty when the id is not a captured item. */
    public Optional<ItemIcon> itemIcon(String itemId) {
        if (!this.definitions.getOrDefault(RegistryIds.ITEM, Map.of()).containsKey(itemId)) return Optional.empty();
        PackCatalog.ItemAppearance appearance = this.catalog.itemAppearances().get(itemId);
        String model = appearance == null || appearance.model().isEmpty() ? ItemIconService.itemModel(itemId) : appearance.model();
        return Optional.of(new ItemIcon(model, appearance == null ? Map.of() : appearance.tints()));
    }
}
