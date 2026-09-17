package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class PostfixVarCompletionTest {
    public interface EventListener { }
    public static class Holder {
        public ConcurrentHashMap<Object, List<EventListener>> listeners;
        public List<? extends EventListener[]> wildcard;
        public EventListener[][] matrix;
        public List<? extends EventListener> upper;
        public List<? super EventListener> lower;
    }
    private static ClassIndex index;
    @BeforeAll static void open() throws Exception {
        index = JavaAnalysisFixtures.index(PostfixVarCompletionTest.class, Holder.class, EventListener.class,
                ConcurrentHashMap.class, List.class);
        CompanionClassIndex.set(index);
    }
    @AfterAll static void close() { CompanionClassIndex.clear(); index.close(); }

    @Test void recursivelyImportsTheEntireExtractedGenericType() throws Exception {
        String result = extract("", "listeners");
        assertTrue(result.contains("import java.util.concurrent.ConcurrentHashMap;"), result);
        assertTrue(result.contains("import java.util.List;"), result);
        assertTrue(result.contains("import " + EventListener.class.getCanonicalName() + ";"), result);
        assertTrue(result.contains("ConcurrentHashMap<Object,List<EventListener>> ${1:name} = ((Holder) null).listeners;"), result);
    }

    @Test void wildcardBoundsAndArraysImportTheirElementType() throws Exception {
        for (String field : List.of("wildcard", "matrix")) {
            String result = extract("", field);
            assertTrue(result.contains("import " + EventListener.class.getCanonicalName() + ";"), result);
            assertFalse(result.contains("import java.lang.Object;"), result);
            assertTrue(result.contains(field.equals("matrix") ? "EventListener[][] ${1:name}" : "List<? extends EventListener[]> ${1:name}"), result);
        }
    }

    @Test void extractedWildcardValuesUseAValidStandaloneBound() throws Exception {
        String upper = extract("", "upper.get(0)");
        assertTrue(upper.contains("EventListener ${1:name}"), upper);
        assertTrue(upper.contains("import " + EventListener.class.getCanonicalName() + ";"), upper);
        String lower = extract("", "lower.get(0)");
        assertTrue(lower.contains("Object ${1:name}"), lower);
    }

    @Test void existingImportConflictsDoNotProduceAnAmbiguousSimpleName() throws Exception {
        String result = extract("import java.util.EventListener;\n", "matrix");
        assertTrue(result.contains("import java.util.EventListener;"), result);
        assertTrue(result.contains(EventListener.class.getCanonicalName() + "[][] ${1:name}"), result);
        assertFalse(result.contains("import " + EventListener.class.getCanonicalName() + ";"), result);
    }

    private static String extract(String imports, String field) throws Exception {
        String text = imports + "import " + Holder.class.getCanonicalName() + ";\n\n((Holder) null)." + field + ".var";
        var generated = JavaSnippetSource.body("Proof", text);
        var unit = new CompilationUnitImpl("Proof", generated.source());
        int offset = generated.sourceMap().toGeneratedOffset(text.length());
        var results = new CompletableFuture<List<CompletionItem>>();
        var request = new CustomCompletionRequestor(unit, offset, (ignored, items) -> results.complete(items));
        unit.codeComplete(offset, request, request);
        var item = results.join().stream().filter(candidate -> candidate.getName().equals("var")).findFirst().orElseThrow();
        var result = new StringBuilder(generated.source());
        item.getTextEdits().stream().sorted(Comparator.comparingInt((CustomTextEdit edit) -> edit.getRange().getOffset()).reversed())
                .forEach(edit -> result.replace(edit.getRange().getOffset(), edit.getRange().getEndOffset(), edit.getNewText()));
        return result.toString();
    }
}
