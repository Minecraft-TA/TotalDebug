package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysis;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaAnalysisFixtures;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.JavaEditorSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SequencedCollection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

public class PostfixCompletionTest {
    public static class ItemStack { }
    public static class Stacks implements Iterable<ItemStack> { public Iterator<ItemStack> iterator() { return null; } }
    public static class Holder {
        public List<ItemStack> stacks;
        public List<? extends ItemStack> upper;
        public List<? super ItemStack> lower;
        public List<List<ItemStack>> nested;
        public static ItemStack[] values() { return null; }
        public static boolean flag() { return true; }
    }
    private static ClassIndex index;
    private static final String IMPORTS = "import " + Holder.class.getCanonicalName() + ";\n";

    @BeforeAll static void open() throws Exception {
        index = JavaAnalysisFixtures.index(PostfixCompletionTest.class, ItemStack.class, Holder.class, Stacks.class,
                Iterable.class, Iterator.class, Collection.class, SequencedCollection.class, List.class, Set.class, Map.class, Map.Entry.class,
                ArrayList.class, Long.class, Short.class, Byte.class, Character.class);
        CompanionClassIndex.set(index);
    }
    @AfterAll static void close() { CompanionClassIndex.clear(); index.close(); }

    @Test void enhancedLoopsInferElementsIncludingPrimitiveAndMultidimensionalArrays() throws Exception {
        assertExpansion("int[] values = null; values.for|", "for", "for (int i : values)");
        assertExpansion("int[][] values = null; values.for|;", "for", "for (int[] aint : values)");
        String result = expand(IMPORTS + "Holder.values().for|;", "for");
        assertTrue(result.contains("import " + ItemStack.class.getCanonicalName() + ";"), result);
        assertTrue(result.contains("for (ItemStack itemstack : Holder.values())"), result);
        assertFalse(result.contains("};"), result);
    }

    @Test void inheritedIterablesWildcardsAndNestedGenericTypesHaveLegalLoopVariables() throws Exception {
        assertExpansion("import " + Stacks.class.getCanonicalName() + ";\nStacks stacks = null; stacks.for|", "for", "ItemStack itemstack : stacks");
        assertExpansion(IMPORTS + "Holder holder = null; holder.upper.for|", "for", "ItemStack");
        assertExpansion(IMPORTS + "Holder holder = null; holder.lower.for|", "for", "Object");
        String nested = expand(IMPORTS + "Holder holder = null; holder.nested.for|", "for");
        assertTrue(nested.contains("import java.util.List;"), nested);
        assertTrue(nested.contains("import " + ItemStack.class.getCanonicalName() + ";"), nested);
        assertTrue(nested.contains("List<ItemStack>"), nested);
        assertExpansion("import java.lang.Iterable; Iterable values = null; values.for|", "for", "Object object : values");
    }

    @Test void fallbackElementTypesRespectLocalTypeNameConflicts() throws Exception {
        assertExpansion("class Object {} Iterable values=null; values.for|", "for", "for (java.lang.Object object : values)");
        assertExpansion("import java.util.List; class Object {} List<? super String> values=null; values.for|", "for", "for (java.lang.Object object : values)");
    }

    @Test void indexedLoopsUseArrayLengthListSizeAndIntegralBounds() throws Exception {
        assertExpansion("int[] values=null; values.fori|;", "fori", "for (int i = 0; i < values.length; i++)");
        assertExpansion(IMPORTS + "Holder holder=null; holder.stacks.fori|", "fori", "i < holder.stacks.size()");
        assertExpansion("int count=5; count.fori|", "fori", "i < count");
        assertExpansion("long count=5; count.fori|", "fori", "for (long i = 0; i < count; i++)");
        assertExpansion("Integer count=5; count.fori|", "fori", "for (int i = 0; i < count; i++)");
        assertExpansion("(5).fori|", "fori", "i < (5)");
        assertExpansion("5.fori|", "fori", "i < 5");
        assertExpansion("0x10.fori|", "fori", "i < 0x10");
        assertExpansion("5L.forr|", "forr", "long i = 5L; i > 0;");
    }

    @Test void reverseLoopsMatchArrayIndicesAndCountDownBounds() throws Exception {
        assertExpansion("int[] values=null; values.forr|", "forr", "i = values.length - 1; i >= 0; i--");
        assertExpansion("int count=5; count.forr|", "forr", "i = count; i > 0; i--");
        assertExpansion("long count=0; count.forr|", "forr", "for (long i = count; i > 0;");
    }

    @Test void indexedComputedReceiversAreEvaluatedOnce() throws Exception {
        String result = expand(IMPORTS + "Holder.values().fori|", "fori");
        assertTrue(result.contains("int i = 0, limit = Holder.values().length; i < limit;"), result);
        assertEquals(1, result.split("Holder.values\\(\\)", -1).length - 1);
    }

    @Test void loopNamesAvoidExistingAndLaterDeclarations() throws Exception {
        assertExpansion("int i=0; int[] values=null; values.fori|; int j=1;", "fori", "for (int k = 0;");
        assertExpansion(IMPORTS + "int itemstack=0; Holder.values().for|", "for", "ItemStack itemstack1 :");
        assertExpansion("for (int i=0;i<2;i++) { int[] values=null; values.fori|; }", "fori", "for (int j = 0;");
    }

    @Test void conditionsNullChecksAndNegationRespectTypeAndExpressionContexts() throws Exception {
        assertExpansion("boolean flag=true; flag.if|;", "if", "if (flag)");
        assertExpansion("Boolean flag=true; flag.else|", "else", "if (!flag)");
        assertExpansion("Object value=null; value.nn|;", "nn", "if (value != null)");
        assertExpansion("Object value=null; value.null|;", "null", "if (value == null)");
        assertExpansion("boolean flag=true; boolean value=flag.not|;", "not", "boolean value=!flag;");
        assertExpansion("boolean a=true,b=false; boolean value=(a && b).not|;", "not", "!(a && b)");
    }

    @Test void doNotOfferStatementTemplatesInsideArgumentsConditionsOrInitializers() throws Exception {
        for (String text : List.of("int[] values=null; Object x=values.for|;", "int[] values=null; return values.for|;",
                "int[] values=null; logln(values.for|);", "int[] values=null; if (values.for|) {}",
                "int[] values=null; if (true) values.for|; else logln(1);")) {
            assertFalse(names(text).contains("for"), text);
        }
    }

    @Test void doNotOfferIncompatibleTemplatesOrTypeReferences() throws Exception {
        for (String text : List.of("String text=\"hello\"; text.for|", "double value=1; value.fori|", "int value=1; value.nn|",
                "int value=1; value.if|", "String.for|", "Unknown value=null; value.for|", "// items.for|", "int value = 10; // 5.fori|")) {
            assertTrue(names(text).stream().noneMatch(Set.of("for", "fori", "nn", "if")::contains), text);
        }
        assertFalse(names("import java.util.Set; Set<String> values=null; values.fori|").contains("fori"));
    }

    @Test void prefixAndMiddleOfTokenReplacementPreserveTheFollowingStatement() throws Exception {
        var names = names("int[] values=null; values.fo|");
        assertTrue(names.containsAll(List.of("for", "fori", "forr")), names.toString());
        String result = expand("int[] values=null; values.fo|ri; int next=1;", "fori");
        assertTrue(result.contains("int next=1;"), result);
        assertFalse(result.contains("}ri"), result);
    }

    @Test void loggingAndSourceDollarSignsSurviveSnippetExpansion() throws Exception {
        assertExpansion("int count=1; count.logln|;", "logln", "logln(count);");
        assertExpansion("int count=1; count.sout|", "sout", "logln(count);");
        assertExpansion("\"${1} ${0} ${$} $value\".logln|", "logln", "logln(\"${1} ${0} ${$} $value\");");
        assertExpansion("int[] $values=null; $values.fori|", "fori", "i < $values.length");
    }

    @Test void loopExpansionBeforeAnotherLineDoesNotRequireAnExistingSemicolon() throws Exception {
        assertExpansion("int[] values=null;\nvalues.for|\nlogln(1);", "for", "for (int i : values)");
    }

    @Test void commentsAroundThePostfixAndSemicolonArePreserved() throws Exception {
        String result = expand("int[] values=null; values /* receiver */. /* suffix */for| /* tail */;", "for");
        for (String comment : List.of("/* receiver */", "/* suffix */", "/* tail */")) assertTrue(result.contains(comment), result);
        assertExpansion("int[] values=null; values // receiver\n.for|", "for", "// receiver");
    }

    @Test void varExtractionStaysAfterEarlierDeclarationsOnTheSameLine() throws Exception {
        assertExpansion("int a=1,b=2; logln((a+b).var|);", "var", "int a=1,b=2; int name = (a+b);\nlogln(name);");
        assertExpansion("int value=1; return value.var|;", "var", "int value=1; int name = value;\nreturn name;");
        assertFalse(names("int a=1,b=(a+1).var|;").contains("var"), "Extraction cannot move an initializer before the local it uses");
    }

    @Test void plainVariableVarStillImportsItsType() throws Exception {
        assertExpansion("int value=1;\nvalue.var|", "var", "int name = value;");
        assertExpansion("null.var|", "var", "Object name = null;");
        assertExpansion("int value=1; if (true) { value.var|; }", "var", "{ int name = value; }");
        assertExpansion("int name=0; int value=1; value.var|;", "var", "int name1 = value;");
        assertExpansion("int value=1; value.var| /* comment */;", "var", "int name = value; /* comment */");
    }

    private static Set<String> names(String marked) throws Exception {
        return proposals(marked).stream().filter(item -> item.proposal == null).map(CompletionItem::getName).collect(Collectors.toSet());
    }

    private static List<CompletionItem> proposals(String marked) throws Exception {
        int caret = marked.indexOf('|');
        var generated = JavaSnippetSource.body("Proof", marked.replace("|", ""));
        var unit = new CompilationUnitImpl("Proof", generated.source());
        var result = new CompletableFuture<List<CompletionItem>>();
        int offset = generated.sourceMap().toGeneratedOffset(caret);
        var request = new CustomCompletionRequestor(unit, offset, (ignored, items) -> result.complete(items));
        unit.codeComplete(offset, request, request);
        return result.join();
    }

    private static void assertExpansion(String marked, String key, String expected) throws Exception {
        String result = expand(marked, key);
        assertTrue(result.contains(expected), result);
    }

    private static String expand(String marked, String key) throws Exception {
        var item = proposals(marked).stream().filter(candidate -> candidate.proposal == null && candidate.getName().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + key + " in " + marked));
        var generated = JavaSnippetSource.body("Proof", marked.replace("|", ""));
        var text = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea(generated.source());
            try (var snippets = new SnippetCompletionAdapter(editor)) {
                var edits = item.getTextEdits().stream().filter(CustomTextEdit::isSnippet).toArray(CustomTextEdit[]::new);
                if (edits.length > 0) snippets.insert(edits);
                for (var edit : item.getTextEdits()) if (!edit.isSnippet()) editor.replaceRange(edit.getNewText(), edit.getRange().getOffset(), edit.getRange().getEndOffset());
                text.set(editor.getText());
            }
        });
        var analysis = JavaAnalysis.parse("Proof", text.get(), JavaEditorSource.identity(text.get()), 0, CompanionClassIndex.identity());
        var errors = analysis.problems().stream().filter(problem -> problem.level() == ParserNotice.Level.ERROR).toList();
        assertTrue(errors.isEmpty(), text.get() + "\n" + errors);
        return text.get();
    }
}
