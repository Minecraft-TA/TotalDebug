package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.core.runtime.OperationCanceledException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

public class SubtypeCompletionTest {
    public interface Bus { void register(); }
    public static class EventBus implements Bus {
        private Object listeners;
        private void removeListeners(int count) { }
        public void register() { }
    }
    public static class AlternativeBus implements Bus {
        private Object listeners;
        public void register() { }
    }
    private static class HiddenBus implements Bus {
        public Object listeners;
        public void register() { }
    }
    public static class ChildBus extends EventBus { }
    public interface Extra { default void specialMethod() { } }
    public static class InheritedBus implements Bus, Extra { public void register() { } }
    public static class Holder {
        public static Bus BUS;
        public static Bus next() { return null; }
        public static Enumeration<List<String>> enumeration() { return null; }
    }
    public static final class ObjectEnumeration implements Enumeration<Object> {
        public boolean hasMoreElements() { return false; }
        public Object nextElement() { return null; }
    }
    public interface Channel<T> { T value(); }
    public static class GenericChannel<T> implements Channel<T> {
        private T payload;
        public T value() { return null; }
    }
    public static class IntegerChannel implements Channel<Integer> {
        private Integer payload;
        public Integer value() { return null; }
    }
    public static class BoundedChannel<T extends Number> implements Channel<T> {
        private T payload;
        public T value() { return null; }
    }

    private static final String IMPORTS = "import " + SubtypeCompletionTest.class.getName() + ".Bus;\n"
            + "import " + SubtypeCompletionTest.class.getName() + ".Holder;\n"
            + "import " + SubtypeCompletionTest.class.getName() + ".Channel;\n";

    @BeforeAll static void index() throws Exception {
        var bytes = new ArrayList<byte[]>();
        var types = new ArrayList<>(List.of(SubtypeCompletionTest.class, Object.class, String.class, Integer.class,
                Number.class, Comparable.class, Enumeration.class, Iterator.class, List.class, StringTokenizer.class));
        types.addAll(List.of(SubtypeCompletionTest.class.getDeclaredClasses()));
        for (var type : types) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                bytes.add(stream.readAllBytes());
            }
        }
        CompanionClassIndex.set(ClassIndex.fromBytes(bytes));
    }
    @AfterAll static void close() { CompanionClassIndex.get().close(); CompanionClassIndex.clear(); }

    @Test void missingRecoveredCompletionNodeDoesNotAbortTheRequest() {
        var unit = new CompilationUnitImpl("Proof", "class Proof {}");
        var request = new CustomCompletionRequestor(unit, 0, (ignored, items) -> { });
        request.beginTask("test", 1);
        request.acceptContext(new InternalCompletionContext() {
            @Override public char[] getToken() { return "as".toCharArray(); }
            @Override public ASTNode getCompletionNode() { return null; }
        });
        assertTrue(SubtypeCompletion.find(unit, request, List.of()).isEmpty());
    }

    @Test void incompatibleGenericSubtypeCannotDiscardDirectChainedMethodCompletions() throws Exception {
        for (String expression : List.of("Holder.enumeration().as|", "Holder.enumeration().as|;", "Holder.enumeration().as|Iterator()")) {
            var items = complete(expression);
            assertTrue(items.stream().anyMatch(item -> item.getName().equals("asIterator") && item.getCastType().isEmpty()), expression);
        }
    }

    @Test void qualifiedInterfaceReceiverOffersDistinctImplementationsAndImportsTheChosenCast() throws Exception {
        String script = "Holder.BUS.liste|;";
        var items = complete(script).stream().filter(item -> item.getName().equals("listeners")).toList();
        assertEquals(2, items.size());
        assertEquals(2, items.stream().map(CompletionItem::getIdentity).distinct().count());
        var chosen = items.stream().filter(item -> item.getCastType().endsWith("EventBus")).findFirst().orElseThrow();
        assertTrue(Flags.isPrivate(chosen.getModifiers()));
        assertTrue(chosen.getLabel().contains("cast to"));
        String inserted = apply(script, chosen);
        assertTrue(inserted.contains("((EventBus) Holder.BUS).listeners;"), inserted);
        assertTrue(inserted.contains("import " + EventBus.class.getCanonicalName() + ";"), inserted);
        assertFalse(items.stream().anyMatch(item -> item.getCastType().contains("HiddenBus")));
    }

    @Test void preservesExistingArgumentsAndIdentifierSuffixAndEvaluatesTheReceiverOnce() throws Exception {
        String script = "Holder.next().removeLis|teners(2);";
        var item = complete(script).stream().filter(candidate -> candidate.getName().equals("removeListeners")).findFirst().orElseThrow();
        String inserted = apply(script, item);
        assertTrue(inserted.contains("((EventBus) Holder.next()).removeListeners(2);"), inserted);
        assertEquals(1, inserted.split("Holder.next\\(\\)", -1).length - 1);
    }

    @Test void preservesGenericArgumentsAndRejectsIncompatibleImplementationsAndBounds() throws Exception {
        String script = "Channel<String> channel = null; channel.pay|;";
        var items = complete(script).stream().filter(item -> item.getName().equals("payload")).toList();
        assertEquals(1, items.size(), items.stream().map(CompletionItem::getLabel).toList().toString());
        var item = items.getFirst();
        assertEquals("String", item.getType());
        assertTrue(item.getCastType().contains("GenericChannel<String>"), item.getCastType());
        assertTrue(apply(script, item).contains("((GenericChannel<String>) channel).payload;"));
    }

    @Test void wildcardReceiverKeepsWildcardCast() throws Exception {
        var item = complete("Channel<?> channel = null; channel.pay|;").stream()
                .filter(candidate -> candidate.getCastType().contains("GenericChannel")).findFirst().orElseThrow();
        assertTrue(item.getCastType().contains("<?>"), item.getCastType());
    }

    @Test void directMembersAreNotDuplicatedForOverrides() throws Exception {
        var items = complete("Holder.BUS.reg|();").stream().filter(item -> item.getName().equals("register")).toList();
        assertEquals(1, items.size());
        assertEquals("", items.getFirst().getCastType());
    }

    @Test void offersExtraMembersInheritedFromAnotherInterface() throws Exception {
        for (String script : List.of("Holder.BUS.spec|;", "Holder.BUS.spec|();")) {
            var item = complete(script).stream().filter(candidate -> candidate.getName().equals("specialMethod")).findFirst().orElseThrow();
            assertTrue(item.getCastType().endsWith("InheritedBus"), item.getCastType());
            assertTrue(apply(script, item).contains("((InheritedBus) Holder.BUS).specialMethod()"));
        }
    }

    @Test void localTypeShadowingUsesTheQualifiedCastInsteadOfTheWrongType() throws Exception {
        String script = "class EventBus {} Holder.BUS.liste|;";
        var item = complete(script).stream().filter(candidate -> candidate.getName().equals("listeners")
                && candidate.getCastType().endsWith("EventBus")).findFirst().orElseThrow();
        String inserted = apply(script, item);
        assertTrue(inserted.contains("((" + EventBus.class.getCanonicalName() + ") Holder.BUS).listeners;"), inserted);
    }

    @Test void preservesCommentsBetweenTheMemberNameAndExistingArguments() throws Exception {
        String script = "Holder.BUS.spec|ialMethod /* keep */ ();";
        var item = complete(script).stream().filter(candidate -> candidate.getName().equals("specialMethod")).findFirst().orElseThrow();
        String inserted = apply(script, item);
        assertTrue(inserted.contains("((InheritedBus) Holder.BUS).specialMethod /* keep */ ();"), inserted);
    }

    @Test void explicitCastDoesNotReceiveAnotherCast() throws Exception {
        String script = "import " + EventBus.class.getCanonicalName() + ";\n((EventBus) Holder.BUS).liste|;";
        var item = complete(script).stream().filter(candidate -> candidate.getName().equals("listeners")).findFirst().orElseThrow();
        assertEquals("", item.getCastType());
        assertTrue(apply(script, item).contains("((EventBus) Holder.BUS).listeners;"));
    }

    @Test void existingInstanceofProposalsUseTheSameCastPresentationAndEdits() throws Exception {
        String script = "import " + EventBus.class.getCanonicalName() + ";\nBus bus = Holder.BUS; if (bus instanceof EventBus) { bus.liste|; }";
        var items = complete(script).stream().filter(candidate -> candidate.getName().equals("listeners")).toList();
        assertEquals(1, items.size());
        assertTrue(items.getFirst().getLabel().contains("cast to"));
        assertTrue(apply(script, items.getFirst()).contains("((EventBus) bus).listeners;"));
    }

    @Test void emptyPrefixAndObjectAndStaticTypeDoNotExpandSubtypes() throws Exception {
        for (String script : List.of("Holder.BUS.|", "Object object = null; object.liste|;", "Bus.liste|;")) {
            assertTrue(complete(script).stream().allMatch(item -> item.getCastType().isEmpty()), script);
        }
    }

    @Test void cancellationProducesNoCallback() throws Exception {
        var source = JavaSnippetSource.body("Cancelled", IMPORTS + "Holder.BUS.liste");
        var unit = new CompilationUnitImpl("Cancelled", source.source());
        var requestor = new CustomCompletionRequestor(unit, source.source().indexOf("Holder.BUS.liste") + "Holder.BUS.liste".length(),
                (ignored, items) -> fail("Cancelled completion must not publish results"));
        requestor.setCanceled(true);
        assertThrows(OperationCanceledException.class, () -> unit.codeComplete(
                source.source().indexOf("Holder.BUS.liste") + "Holder.BUS.liste".length(), requestor, requestor));
    }

    @Test void castAndImportRemainOneUndoableOperation() throws Exception {
        String script = "Holder.BUS.liste|;";
        var item = complete(script).stream().filter(candidate -> candidate.getCastType().endsWith("EventBus")
                && candidate.getName().equals("listeners")).findFirst().orElseThrow();
        String original = generated(script);
        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea(original);
            editor.discardAllEdits();
            editor.beginAtomicEdit();
            try {
                for (var edit : orderedEdits(item)) editor.replaceRange(edit.getNewText(), edit.getRange().getOffset(), edit.getRange().getEndOffset());
            } finally { editor.endAtomicEdit(); }
            assertTrue(editor.getText().contains("((EventBus) Holder.BUS).listeners;"));
            editor.undoLastAction();
            assertEquals(original, editor.getText());
        });
    }

    private static List<CompletionItem> complete(String script) throws Exception {
        String combined = IMPORTS + script;
        int caret = combined.indexOf('|');
        var source = JavaSnippetSource.body("SubtypeProof", combined.replace("|", ""));
        var unit = new CompilationUnitImpl("SubtypeProof", source.source());
        var result = new CompletableFuture<List<CompletionItem>>();
        int offset = source.sourceMap().toGeneratedOffset(caret);
        var requestor = new CustomCompletionRequestor(unit, offset, (ignored, items) -> result.complete(items));
        unit.codeComplete(offset, requestor, requestor);
        return result.join();
    }

    private static String generated(String script) { return JavaSnippetSource.body("SubtypeProof", IMPORTS + script.replace("|", "")).source(); }
    private static List<CustomTextEdit> orderedEdits(CompletionItem item) {
        return item.getTextEdits().stream().sorted(Comparator.comparingInt((CustomTextEdit edit) -> edit.getRange().getOffset()).reversed()).toList();
    }
    private static String apply(String script, CompletionItem item) {
        var result = new StringBuilder(generated(script));
        for (var edit : orderedEdits(item)) result.replace(edit.getRange().getOffset(), edit.getRange().getEndOffset(), edit.getNewText());
        return result.toString();
    }
}
