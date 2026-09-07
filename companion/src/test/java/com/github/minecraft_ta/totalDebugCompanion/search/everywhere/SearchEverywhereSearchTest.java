package com.github.minecraft_ta.totalDebugCompanion.search.everywhere;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.IndexedReferenceSearch;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchEverywhereSearchTest {
    @Test
    void searchesRealIndexDomainsAndAppliesSourcesBeforeTheLimit() throws Exception {
        try (ClassIndex index = ClassIndex.fromSources(List.of(
                IndexSource.classFile(10, classBytes(AlphaSearchFixture.class)),
                IndexSource.classFile(20, classBytes(BetaSearchFixture.class))
        ))) {
            SearchEverywhereSearch search = new SearchEverywhereSearch();

            List<SearchEverywhereSearch.Result> classes = search.search(
                    index, "Alpha", SearchEverywhereSearch.Category.CLASSES, 10, new int[]{10}
            );
            assertEquals(1, classes.size());
            assertEquals(AlphaSearchFixture.class.getName(),
                    assertInstanceOf(SearchEverywhereSearch.ClassResult.class, classes.getFirst()).binaryName());

            List<SearchEverywhereSearch.Result> symbols = search.search(
                    index, "matching", SearchEverywhereSearch.Category.SYMBOLS, 10, new int[]{10}
            );
            assertEquals(2, symbols.size());
            assertTrue(symbols.stream()
                    .map(SearchEverywhereSearch.SymbolResult.class::cast)
                    .allMatch(result -> result.ownerBinaryName().equals(AlphaSearchFixture.class.getName())));

            List<SearchEverywhereSearch.Result> text = search.search(
                    index, "selected-text", SearchEverywhereSearch.Category.TEXT, 10, new int[]{10}
            );
            SearchEverywhereSearch.TextResult literal = assertInstanceOf(
                    SearchEverywhereSearch.TextResult.class,
                    text.getFirst()
            );
            assertEquals("selected-text-value", literal.value());
            assertEquals(10, literal.sourceIds()[0]);
            assertEquals(
                    10,
                    new IndexedReferenceSearch(index)
                            .search(ReferenceQuery.stringLiteral("selected-text-value"), 10)
                            .usages()
                            .getFirst()
                            .sourceId()
            );
            assertTrue(search.search(
                    index, "selected-text", SearchEverywhereSearch.Category.TEXT, 10, new int[]{20}
            ).isEmpty());
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = '/' + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resource);
            }
            return input.readAllBytes();
        }
    }
}
