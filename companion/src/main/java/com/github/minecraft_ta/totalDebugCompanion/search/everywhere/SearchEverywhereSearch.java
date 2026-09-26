package com.github.minecraft_ta.totalDebugCompanion.search.everywhere;

import com.github.minecraft_ta.totalDebugCompanion.catalog.CatalogIndex;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.catalog.RegistryIds;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.LiteralSearchResult;
import com.github.tth05.jindex.SearchOptions;
import com.github.tth05.jindex.SymbolKind;
import com.github.tth05.jindex.SymbolSearchResult;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the query behind Search Everywhere over the runtime class index and the captured pack catalog, and
 * converts native-backed results into immutable rows.
 */
public final class SearchEverywhereSearch {
    public enum Category {
        ALL("All", true, true),
        MODS("Mods", false, true),
        ITEMS("Items", false, true),
        BLOCKS("Blocks", false, true),
        ENTITIES("Entities", false, true),
        RESOURCES("Resources", false, true),
        KEY_BINDINGS("Key bindings", false, true),
        CLASSES("Classes", true, false),
        SYMBOLS("Symbols", true, false),
        TEXT("Text", true, false);

        private final String label;
        private final boolean usesIndex;
        private final boolean usesCatalog;

        Category(String label, boolean usesIndex, boolean usesCatalog) {
            this.label = label;
            this.usesIndex = usesIndex;
            this.usesCatalog = usesCatalog;
        }

        public String label() {
            return this.label;
        }

        /** Whether the category searches the runtime class index. */
        public boolean usesIndex() {
            return this.usesIndex;
        }

        /** Whether the category searches the captured pack catalog. */
        public boolean usesCatalog() {
            return this.usesCatalog;
        }
    }

    public sealed interface Result permits ClassResult, SymbolResult, TextResult, ModResult, DefinitionResult, ResourceResult,
            KeyBindingResult {
        String searchableName();
    }

    /** An installed mod. */
    public record ModResult(String modId, String title, String version) implements Result {
        @Override
        public String searchableName() {
            return this.title;
        }
    }

    /** A registered block, item or entity type; {@code icon} is null when it has no item to draw. */
    public record DefinitionResult(CatalogIndex.Entry entry, String owner, CatalogIndex.ItemIcon icon) implements Result {
        public DefinitionResult {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(owner, "owner");
        }

        @Override
        public String searchableName() {
            return this.entry.title();
        }
    }

    /** A key binding: {@code name} as {@code options.txt} writes it, the action it performs, its key and its mod. */
    public record KeyBindingResult(String name, String action, String key, String owner) implements Result {
        public KeyBindingResult {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(owner, "owner");
        }

        @Override
        public String searchableName() {
            return this.action;
        }
    }

    /** A resource shipped in a mod file. */
    public record ResourceResult(ModResources.Resource resource, String owner) implements Result {
        public ResourceResult {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(owner, "owner");
        }

        @Override
        public String searchableName() {
            return this.resource.fileName();
        }
    }

    public record ClassResult(
            String binaryName,
            String simpleName,
            String packageName,
            int sourceId,
            int accessFlags
    ) implements Result {
        public ClassResult {
            Objects.requireNonNull(binaryName, "binaryName");
            Objects.requireNonNull(simpleName, "simpleName");
            Objects.requireNonNull(packageName, "packageName");
        }

        @Override
        public String searchableName() {
            return this.simpleName;
        }

        public int[] sourceIds() {
            return new int[]{this.sourceId};
        }

        public boolean isInterface() {
            return Modifier.isInterface(this.accessFlags);
        }

        public boolean isEnum() {
            return (this.accessFlags & 0x00004000) != 0;
        }
    }

    public record SymbolResult(
            SymbolKind kind,
            String ownerBinaryName,
            String name,
            String descriptor,
            int sourceId,
            int accessFlags
    ) implements Result {
        public SymbolResult {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ownerBinaryName, "ownerBinaryName");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String searchableName() {
            return this.name;
        }

        public int[] sourceIds() {
            return new int[]{this.sourceId};
        }
    }

    public record TextResult(String value, int[] sourceIds) implements Result {
        public TextResult {
            Objects.requireNonNull(value, "value");
            sourceIds = Objects.requireNonNull(sourceIds, "sourceIds").clone();
        }

        @Override
        public String searchableName() {
            return this.value;
        }

        public int[] sourceIds() {
            return this.sourceIds.clone();
        }
    }

    /**
     * Searches the class index and the catalog for the category; either may be null when it is unavailable.
     * {@code sourceIds} and {@code moduleIds} restrict index and catalog results to the selected modules, and are
     * null when every module is selected.
     */
    public List<Result> search(
            ClassIndex index,
            CatalogSearch catalog,
            String query,
            Category category,
            int limit,
            int[] sourceIds,
            Set<String> moduleIds
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(category, "category");
        if (limit < 1) {
            throw new IllegalArgumentException("Search result limit must be positive");
        }
        if (query.isBlank()) {
            return List.of();
        }

        SearchOptions options = SearchOptions.with(
                SearchOptions.SearchMode.CONTAINS,
                SearchOptions.MatchMode.IGNORE_CASE,
                limit
        );
        ArrayList<Result> results = new ArrayList<>(limit * 2);
        if (catalog != null && category.usesCatalog()) {
            results.addAll(catalog.search(query, category, limit, moduleIds));
        }
        boolean searchIndex = index != null && category.usesIndex()
                && (category == Category.TEXT || query.chars().allMatch(character -> character < 128));
        if (searchIndex && (category == Category.ALL || category == Category.CLASSES)) {
            IndexedClass[] classes = sourceIds == null
                    ? index.findClasses(query, options).results()
                    : index.findClasses(query, options, sourceIds).results();
            Arrays.stream(classes).map(SearchEverywhereSearch::classResult).forEach(results::add);
        }
        if (searchIndex && (category == Category.ALL || category == Category.SYMBOLS)) {
            SymbolSearchResult[] symbols = sourceIds == null
                    ? index.findSymbols(query, options, EnumSet.of(SymbolKind.FIELD, SymbolKind.METHOD)).results()
                    : index.findSymbols(query, options, EnumSet.of(SymbolKind.FIELD, SymbolKind.METHOD), sourceIds).results();
            Arrays.stream(symbols).map(SearchEverywhereSearch::symbolResult).forEach(results::add);
        }
        if (searchIndex && category == Category.TEXT) {
            LiteralSearchResult[] literals = sourceIds == null
                    ? index.findLiteralsContaining(query, limit).results()
                    : index.findLiteralsContaining(query, limit, sourceIds).results();
            Arrays.stream(literals)
                    .map(result -> new TextResult(result.value(), result.sourceIds()))
                    .forEach(results::add);
        }

        Comparator<Result> ranking = Comparator
                .comparingInt((Result result) -> rank(result, query))
                .thenComparingInt(SearchEverywhereSearch::kindRank)
                .thenComparing(Result::searchableName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SearchEverywhereSearch::stableIdentity);
        return results.stream().sorted(ranking).limit(limit).toList();
    }

    private static ClassResult classResult(IndexedClass indexedClass) {
        String binaryName = indexedClass.getNameWithPackageDot();
        int separator = binaryName.lastIndexOf('.');
        return new ClassResult(
                binaryName,
                binaryName.substring(separator + 1).replace('$', '.'),
                separator < 0 ? "" : binaryName.substring(0, separator),
                indexedClass.getSourceId(),
                indexedClass.getAccessFlags()
        );
    }

    private static SymbolResult symbolResult(SymbolSearchResult result) {
        return new SymbolResult(
                result.kind(),
                result.ownerInternalName().replace('/', '.'),
                result.name(),
                result.descriptor(),
                result.sourceId(),
                result.accessFlags()
        );
    }

    private static int matchRank(String value, String query) {
        String foldedValue = value.toLowerCase(Locale.ROOT);
        String foldedQuery = query.toLowerCase(Locale.ROOT);
        if (foldedValue.equals(foldedQuery)) {
            return 0;
        }
        if (foldedValue.startsWith(foldedQuery)) {
            return 1;
        }
        return 2 + Math.max(0, foldedValue.indexOf(foldedQuery));
    }

    /** How well a result matches; registered content also matches by the path of its registry id. */
    static int rank(Result result, String query) {
        int byName = matchRank(result.searchableName(), query);
        return switch (result) {
            case ModResult mod -> Math.min(byName, matchRank(mod.modId(), query));
            case DefinitionResult definition -> Math.min(byName,
                    matchRank(definition.entry().id().substring(definition.entry().id().indexOf(':') + 1), query));
            default -> byName;
        };
    }

    private static int kindRank(Result result) {
        return switch (result) {
            case ModResult ignored -> 0;
            case DefinitionResult definition -> switch (definition.entry().registry()) {
                case RegistryIds.ITEM -> 1;
                case RegistryIds.BLOCK -> 2;
                default -> 3;
            };
            case ClassResult ignored -> 4;
            case SymbolResult symbol -> symbol.kind() == SymbolKind.METHOD ? 5 : 6;
            case TextResult ignored -> 7;
            case ResourceResult ignored -> 8;
            case KeyBindingResult ignored -> 9;
        };
    }

    private static String stableIdentity(Result result) {
        return switch (result) {
            case ClassResult type -> type.binaryName();
            case SymbolResult symbol -> symbol.ownerBinaryName() + '#' + symbol.name() + symbol.descriptor();
            case TextResult text -> text.value();
            case ModResult mod -> mod.modId();
            case DefinitionResult definition -> definition.entry().subject().format();
            case ResourceResult resource -> resource.resource().file() + "!" + resource.resource().path();
            case KeyBindingResult key -> key.name();
        };
    }
}
