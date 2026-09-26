package com.github.minecraft_ta.totalDebugCompanion.search.everywhere;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.KeyBindings;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Category;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.DefinitionResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.KeyBindingResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ModResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.ResourceResult;
import com.github.minecraft_ta.totalDebugCompanion.search.everywhere.SearchEverywhereSearch.Result;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finds mods, registered content and resources in one captured pack catalog by name, id or path. A linear scan over
 * lower-cased names takes a few milliseconds even for large packs. Resources are listed on first use.
 */
public final class CatalogSearch {
    private final CatalogIndex index;
    private final List<CatalogIndex.Entry> entries;
    private final String[] names;
    private final String[] ids;
    private List<ModResources.Resource> resources;
    private List<Path> directories;
    /** Where the game keeps its keys, or null without a game directory. */
    private final Path options;

    /** {@code options} is the game's {@code options.txt}, read for the keys the bindings have now; null for none. */
    public CatalogSearch(CatalogIndex index, Path options) {
        this.index = Objects.requireNonNull(index, "index");
        this.options = options;
        this.entries = index.entries();
        this.names = new String[this.entries.size()];
        this.ids = new String[this.entries.size()];
        for (int i = 0; i < this.entries.size(); i++) {
            this.names[i] = this.entries.get(i).name().toLowerCase(Locale.ROOT);
            this.ids[i] = this.entries.get(i).id();
        }
    }

    public CatalogIndex index() {
        return this.index;
    }

    /**
     * The best {@code limit} matches; {@code moduleIds} limits results to mods in those runtime modules and null
     * accepts every mod.
     */
    List<Result> search(String query, Category category, int limit, Set<String> moduleIds) {
        String folded = query.toLowerCase(Locale.ROOT);
        List<Result> results = new ArrayList<>();
        if (category == Category.ALL || category == Category.MODS) {
            for (PackCatalog.Mod mod : this.index.mods()) {
                if (!accepts(mod, moduleIds)) continue;
                if (mod.title().toLowerCase(Locale.ROOT).contains(folded) || mod.id().contains(folded)) {
                    results.add(new ModResult(mod.id(), mod.title(), mod.version()));
                }
            }
        }
        String registry = switch (category) {
            case ITEMS -> RegistryIds.ITEM;
            case BLOCKS -> RegistryIds.BLOCK;
            case ENTITIES -> RegistryIds.ENTITY_TYPE;
            default -> null;
        };
        if (category == Category.ALL || registry != null) {
            for (int i = 0; i < this.entries.size(); i++) {
                CatalogIndex.Entry entry = this.entries.get(i);
                if (registry != null && !entry.registry().equals(registry)) continue;
                if (!this.names[i].contains(folded) && !this.ids[i].contains(folded)) continue;
                PackCatalog.Mod owner = this.index.mod(entry.namespace()).orElse(null);
                if (moduleIds != null && (owner == null || !moduleIds.contains(owner.module()))) continue;
                results.add(new DefinitionResult(entry, this.index.ownerName(entry.namespace()),
                        entry.iconItem().isEmpty() ? null : this.index.itemIcon(entry.iconItem()).orElse(null)));
            }
        }
        if (category == Category.ALL || category == Category.KEY_BINDINGS) {
            KeyBindings bindings = keyBindings();
            for (KeyBindings.Binding binding : bindings.bindings()) {
                if (!binding.name().toLowerCase(Locale.ROOT).contains(folded) && !binding.spec().name().contains(folded)
                        && !bindings.namesKey(binding.current(), query)) continue;
                String ownerId = this.index.keyBindingOwner(binding.spec());
                PackCatalog.Mod owner = this.index.mod(ownerId).orElse(null);
                if (moduleIds != null && (owner == null || !moduleIds.contains(owner.module()))) continue;
                results.add(new KeyBindingResult(binding.spec().name(), binding.name(), bindings.display(binding.current()),
                        owner == null ? ownerId : owner.title()));
            }
        }
        if (category == Category.RESOURCES) {
            for (ModResources.Resource resource : resources()) {
                if (!resource.path().toLowerCase(Locale.ROOT).contains(folded)) continue;
                String namespace = namespace(resource.path());
                PackCatalog.Mod owner = this.index.mod(namespace).orElse(null);
                if (moduleIds != null && (owner == null || !moduleIds.contains(owner.module()))) continue;
                results.add(new ResourceResult(resource, this.index.ownerName(namespace)));
            }
        }
        // Every match is ranked before the limit applies, so a closer match late in the registry is kept.
        return results.stream()
                .sorted(Comparator.comparingInt((Result result) -> SearchEverywhereSearch.rank(result, query)))
                .limit(limit)
                .toList();
    }

    /** The bindings with the keys {@code options.txt} gives them now, or their defaults when it cannot be read. Blocking. */
    private KeyBindings keyBindings() {
        PackCatalog catalog = this.index.catalog();
        Map<String, KeyBindings.Assignment> current = Map.of();
        if (this.options != null) {
            try {
                current = KeyBindings.readOptions(this.options);
            } catch (IOException unreadable) {
                // The defaults stay searchable.
            }
        }
        return new KeyBindings(catalog.keyBindings(), catalog.keyContexts(), current, catalog.keyNames());
    }

    private static boolean accepts(PackCatalog.Mod mod, Set<String> moduleIds) {
        return moduleIds == null || moduleIds.contains(mod.module());
    }

    private static String namespace(String path) {
        String[] parts = path.split("/", 3);
        return parts.length < 3 ? "" : parts[1];
    }

    /**
     * Every mod file's resources. Archives are listed once per catalog; a development mod's directory is listed each
     * time, since files change inside it. Blocking; on the search worker.
     */
    private synchronized List<ModResources.Resource> resources() {
        if (this.resources == null) {
            List<Path> files = new ArrayList<>();
            for (PackCatalog.Mod mod : this.index.mods()) {
                for (Path file : this.index.resourceFiles(mod.id())) if (!files.contains(file)) files.add(file);
            }
            this.directories = files.stream().filter(Files::isDirectory).toList();
            this.resources = List.copyOf(listed(files.stream().filter(file -> !Files.isDirectory(file)).toList()));
        }
        if (this.directories.isEmpty()) return this.resources;
        List<ModResources.Resource> all = new ArrayList<>(this.resources);
        all.addAll(listed(this.directories));
        return all;
    }

    private static List<ModResources.Resource> listed(List<Path> files) {
        List<ModResources.Resource> listed = new ArrayList<>();
        for (Path file : files) {
            try {
                listed.addAll(ModResources.list(file));
            } catch (IOException unreadable) {
                // An unreadable mod file contributes no resources; its page reports why.
            }
        }
        return listed;
    }
}
