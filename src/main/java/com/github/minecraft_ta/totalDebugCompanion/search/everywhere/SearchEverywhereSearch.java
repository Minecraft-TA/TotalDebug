package com.github.minecraft_ta.totalDebugCompanion.search.everywhere;

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

/** Executes the indexed query behind Search Everywhere and converts native-backed results into immutable rows. */
public final class SearchEverywhereSearch {
    public enum Category {
        ALL("All"),
        CLASSES("Classes"),
        SYMBOLS("Symbols"),
        TEXT("Text");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public sealed interface Result permits ClassResult, SymbolResult, TextResult {
        String searchableName();

        int[] sourceIds();
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

        @Override
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

        @Override
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

        @Override
        public int[] sourceIds() {
            return this.sourceIds.clone();
        }
    }

    public List<Result> search(
            ClassIndex index,
            String query,
            Category category,
            int limit,
            int[] sourceIds
    ) {
        Objects.requireNonNull(index, "index");
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
        if (category == Category.ALL || category == Category.CLASSES) {
            IndexedClass[] classes = sourceIds == null
                    ? index.findClasses(query, options).results()
                    : index.findClasses(query, options, sourceIds).results();
            Arrays.stream(classes).map(SearchEverywhereSearch::classResult).forEach(results::add);
        }
        if (category == Category.ALL || category == Category.SYMBOLS) {
            SymbolSearchResult[] symbols = sourceIds == null
                    ? index.findSymbols(query, options, EnumSet.of(SymbolKind.FIELD, SymbolKind.METHOD))
                    : index.findSymbols(query, options, EnumSet.of(SymbolKind.FIELD, SymbolKind.METHOD), sourceIds);
            Arrays.stream(symbols).map(SearchEverywhereSearch::symbolResult).forEach(results::add);
        }
        if (category == Category.TEXT) {
            LiteralSearchResult[] literals = sourceIds == null
                    ? index.findLiteralsContaining(query, limit).results()
                    : index.findLiteralsContaining(query, limit, sourceIds).results();
            Arrays.stream(literals)
                    .map(result -> new TextResult(result.value(), result.sourceIds()))
                    .forEach(results::add);
        }

        Comparator<Result> ranking = Comparator
                .comparingInt((Result result) -> matchRank(result.searchableName(), query))
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

    private static int kindRank(Result result) {
        return switch (result) {
            case ClassResult ignored -> 0;
            case SymbolResult symbol -> symbol.kind() == SymbolKind.METHOD ? 1 : 2;
            case TextResult ignored -> 3;
        };
    }

    private static String stableIdentity(Result result) {
        return switch (result) {
            case ClassResult type -> type.binaryName();
            case SymbolResult symbol -> symbol.ownerBinaryName() + '#' + symbol.name() + symbol.descriptor();
            case TextResult text -> text.value();
        };
    }
}
