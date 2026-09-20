package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class CompletionScopeTest {
    public interface BusApi { }
    public static class Bus implements BusApi { private Map<Object, List<Runnable>> listeners; }
    public static class Platform { public static BusApi EVENT_BUS; }
    private static ClassIndex index;

    @BeforeAll static void open() throws Exception {
        index = JavaAnalysisFixtures.index(CompletionScopeTest.class, Bus.class, BusApi.class, Platform.class, Map.class, List.class, Runnable.class, Consumer.class);
        CompanionClassIndex.set(index);
    }
    @AfterAll static void close() { CompanionClassIndex.clear(); index.close(); }

    @Test void memberReferencesAndUnknownNamesDoNotBecomeLocals() throws Exception {
        String imports = "import " + Bus.class.getCanonicalName() + ";\nimport " + Platform.class.getCanonicalName() + ";\nimport java.util.Map;\nimport java.util.List;\n";
        for (String preceding : List.of("", "return null;\n", "return \"value\".clone().clone().getClass();\n")) {
            for (String type : List.of("Runnable", "MissingListener")) {
                String script = imports + preceding + "Map<Object, List<" + type + ">> name = ((Bus) Platform.EVENT_BUS).listeners;\nlist|";
                var items = complete(script);
                assertFalse(items.stream().anyMatch(item -> item.getKind() == CompletionItemKind.VARIABLE && item.getName().equals("listeners")),
                        () -> script + "\n" + items.stream().map(CompletionItem::getLabel).toList());
            }
        }
        assertFalse(complete("log(missingValue);\nmiss|").stream().anyMatch(item -> item.getName().equals("missingValue")));
        assertTrue(complete(imports + "((Bus) Platform.EVENT_BUS).list|").stream()
                .anyMatch(item -> item.getName().equals("listeners") && item.getKind() == CompletionItemKind.FIELD));
    }

    @Test void onlyLocalsInScopeAreOffered() throws Exception {
        assertTrue(complete("String listeners = null;\nlist|").stream().anyMatch(item -> item.getName().equals("listeners")));
        assertFalse(complete("{ String listeners = null; }\nlist|").stream().anyMatch(item -> item.getName().equals("listeners")));
        assertFalse(complete("list|\nString listeners = null;").stream().anyMatch(item -> item.getName().equals("listeners")));
        assertTrue(complete("Runnable task = () -> { String listeners = null; list| }; ").stream().anyMatch(item -> item.getName().equals("listeners")));
        assertTrue(complete("MissingType listeners = null; list|").stream().anyMatch(item -> item.getName().equals("listeners")));
        assertTrue(complete("for (String listener : new String[0]) { list| }").stream().anyMatch(item -> item.getName().equals("listener")));
        assertTrue(complete("import java.util.function.Consumer; Consumer<String> action = listener -> { list| }; ").stream().anyMatch(item -> item.getName().equals("listener")));
    }

    @Test void statementKeywordsAreSingleRealTemplatesAndPackagesKeepTheirKind() throws Exception {
        for (String keyword : List.of("if", "for")) {
            var entries = complete(keyword + "|").stream().filter(item -> item.getName().equals(keyword)).toList();
            assertEquals(1, entries.size());
            assertEquals(CompletionItemKind.SNIPPET, entries.getFirst().getKind());
            assertTrue(entries.getFirst().getTextEdits().getFirst().isSnippet());
        }
        assertTrue(complete("ja|").stream().anyMatch(item -> item.getName().equals("java") && item.getKind() == CompletionItemKind.IMPORT));
        assertFalse(complete("String value = if|").stream().anyMatch(item -> item.getKind() == CompletionItemKind.SNIPPET));
    }

    private static List<CompletionItem> complete(String script) throws Exception {
        var source = JavaSnippetSource.body("Proof", script.replace("|", ""));
        var unit = new CompilationUnitImpl("Proof", source.source());
        int offset = source.sourceMap().toGeneratedOffset(script.indexOf('|'));
        var result = new CompletableFuture<List<CompletionItem>>();
        var requestor = new CustomCompletionRequestor(unit, offset, (ignored, items) -> result.complete(items));
        unit.codeComplete(offset, requestor, requestor);
        return result.join();
    }
}
