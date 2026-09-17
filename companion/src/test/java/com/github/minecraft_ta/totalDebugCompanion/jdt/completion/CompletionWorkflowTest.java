package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.NameLookupImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.JavaProjectImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IJavaElementRequestorStub;
import org.eclipse.jdt.core.IType;
import com.github.tth05.jindex.ClassIndex;

import org.eclipse.jdt.core.Flags;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class CompletionWorkflowTest {
    @BeforeAll static void index() throws Exception {
        List<byte[]> classes = new ArrayList<>();
        for (Class<?> type : List.of(Object.class, String.class, Boolean.class, Level.class, Throwable.class, EventListener.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { classes.add(stream.readAllBytes()); }
        }
        for (Class<?> type : Level.class.getDeclaredClasses()) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { classes.add(stream.readAllBytes()); }
        }
        for (String name : List.of("net/minecraft/world/item/ItemStack", "a/mod/ItemStack", "scala/ItemStack",
                "net/minecraft/core/BlockPos", "a/mod/BlockPos", "net/minecraft/world/level/Level",
                "a/mod/ItemStackExtra", "a/mod/SuperItemStack", "net/neoforged/bus/api/EventListener", "a/mod/EventListener")) classes.add(type(name));
        // Enough similarly named types to exercise the result limit with Minecraft last in source order.
        for (int i = 0; i < 60; i++) classes.add(type("a/mod" + i + "/ItemStack"));
        classes.add(program());
        CompanionClassIndex.set(ClassIndex.fromBytes(classes));
    }
    @AfterAll static void close() { CompanionClassIndex.get().close(); CompanionClassIndex.clear(); }

    @Test void constructorPrefixesPublishOneFinalResult() throws Exception {
        assertTrue(complete("new Str|").stream().anyMatch(item -> item.getKind() == CompletionItemKind.CONSTRUCTOR));
    }

    @Test void exactRecoveryLookupDoesNotDisablePartialTypeCompletion() throws Exception {
        assertTrue(complete("Str| value = null;").stream().anyMatch(item -> item.getName().equals("String")));
        assertTrue(complete("ItemStackEx| value = null;").stream().anyMatch(item -> item.getName().equals("ItemStackExtra")));
    }

    @Test void exactRecoveryLookupDoesNotGuessTypesContainingTheMissingName() {
        var found = new ArrayList<String>();
        new NameLookupImpl(new JavaProjectImpl()).seekTypes("ItemStack", null, false, 0, new IJavaElementRequestorStub() {
            @Override public void acceptType(IType type) { found.add(type.getElementName()); }
        }, true);
        assertFalse(found.isEmpty());
        assertTrue(found.stream().allMatch("ItemStack"::equals), found.toString());
    }

    @Test void loaderPreferencePreservesExplicitJavaImportsAndQualifiedSearches() throws Exception {
        assertEquals("net.neoforged.bus.api", complete("EventListe| listener = null;").getFirst().getType());
        assertEquals("java.util", complete("import java.util.EventListener; EventListe| listener = null;").getFirst().getType());
        assertEquals("a.mod", complete("a.mod.EventListe| listener = null;").getFirst().getType());
    }

    @Test void ambiguousTypesFavorMinecraftBeforeTheResultLimit() throws Exception {
        var items = complete("ItemSta| stack = null;");
        assertEquals(50, items.size());
        assertEquals("net.minecraft.world.item", items.getFirst().getType());
        assertEquals("net.minecraft.core", complete("BlockPo| pos = null;").getFirst().getType());
        assertEquals("net.minecraft.world.level", complete("Lev| level = null;").getFirst().getType());
    }

    @Test void explicitImportsAndQualifiedPackagesOverrideOriginPreference() throws Exception {
        assertEquals("a.mod", complete("import a.mod.ItemStack; ItemSta| stack = null;").getFirst().getType());
        assertEquals("java.util.logging", complete("import java.util.logging.Level; Lev| level = null;").getFirst().getType());
        assertEquals("scala", complete("scala.ItemSta| stack = null;").getFirst().getType());
    }

    @Test void discoversPrivateMembersAndPreservesTheirModifierMetadata() throws Exception {
        var field = complete("import net.minecraft.world.item.ItemStack; ItemStack stack = null; stack.cou|").getFirst();
        assertEquals("count", field.getName());
        assertTrue(Flags.isPrivate(field.getModifiers()));
        var constant = complete("import net.minecraft.world.item.ItemStack; ItemStack.EM|").getFirst();
        assertEquals("EMPTY", constant.getName());
        assertTrue(Flags.isStatic(constant.getModifiers()) && Flags.isFinal(constant.getModifiers()));
        assertEquals("length", complete("int[] values = null; values.len|").getFirst().getName());
    }

    @Test void hidesWrapperMembersButKeepsScriptHelpers() throws Exception {
        var items = complete("this.|");
        assertTrue(items.stream().anyMatch(item -> item.getName().equals("log")));
        assertFalse(items.stream().anyMatch(item -> Set.of("run", "noResult", "output").contains(item.getName())));
        assertEquals("sout", complete("sout|").getFirst().getName());
    }

    @Test void completesCallsBesideExistingParentheses() throws Exception {
        for (String suffix : List.of("getCou|();", "getCou|nt();")) {
            String marked = "import net.minecraft.world.item.ItemStack; ItemStack stack = null; stack." + suffix;
            var generated = JavaSnippetSource.body("Proof", marked.replace("|", ""));
            var item = complete(marked).stream().filter(candidate -> candidate.getName().equals("getCount")).findFirst().orElseThrow();
            var source = new StringBuilder(generated.source());
            item.getTextEdits().stream().sorted(Comparator.comparingInt((CustomTextEdit edit) -> edit.getRange().getOffset()).reversed())
                    .forEach(edit -> source.replace(edit.getRange().getOffset(), edit.getRange().getEndOffset(), edit.getNewText().replace("${0}", "")));
            assertTrue(source.toString().contains("stack.getCount();"), source.toString());
        }
    }

    @Test void voidCallCompletionFinishesAfterItsGeneratedSemicolonWithoutAddingANewline() throws Exception {
        String marked = "import net.minecraft.world.item.ItemStack; ItemStack stack = null; stack.clea|";
        var item = complete(marked).stream().filter(candidate -> candidate.getName().equals("clear")).findFirst().orElseThrow();
        var source = JavaSnippetSource.body("Proof", marked.replace("|", "")).source();
        var edit = item.getTextEdits().stream().filter(CustomTextEdit::isSnippet).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea(source);
            int lines = editor.getLineCount();
            new SnippetCompletionAdapter(editor).insert(edit);
            assertTrue(editor.getText().contains("stack.clear();"));
            assertEquals(editor.getText().indexOf("stack.clear();") + "stack.clear();".length(), editor.getCaretPosition());
            assertEquals(lines, editor.getLineCount());
        });
    }

    @Test void voidCallKeepsArgumentSelectionThenTabsPastTheSemicolon() throws Exception {
        String marked = "import net.minecraft.world.item.ItemStack; ItemStack stack = null; stack.setCo|";
        var item = complete(marked).stream().filter(candidate -> candidate.getName().equals("setCount")).findFirst().orElseThrow();
        var source = JavaSnippetSource.body("Proof", marked.replace("|", "")).source();
        var edit = item.getTextEdits().stream().filter(CustomTextEdit::isSnippet).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(() -> {
            var editor = new RSyntaxTextArea(source);
            new SnippetCompletionAdapter(editor).insert(edit);
            assertNotNull(editor.getSelectedText(), "The argument remains the first editing stop");
            editor.replaceSelection("42");
            var tab = editor.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
            editor.getActionMap().get(tab).actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "tab"));
            assertTrue(editor.getText().contains("stack.setCount(42);"));
            assertEquals(editor.getText().indexOf("stack.setCount(42);") + "stack.setCount(42);".length(), editor.getCaretPosition());
        });
    }

    private static List<CompletionItem> complete(String script) throws Exception {
        int caret = script.indexOf('|');
        var source = JavaSnippetSource.body("Proof", script.replace("|", ""));
        var unit = new CompilationUnitImpl("Proof", source.source());
        int offset = source.sourceMap().toGeneratedOffset(caret);
        var result = new CompletableFuture<List<CompletionItem>>();
        var callbacks = new AtomicInteger();
        var requestor = new CustomCompletionRequestor(unit, offset, (ignored, items) -> { callbacks.incrementAndGet(); result.complete(items); });
        unit.codeComplete(offset, requestor, requestor);
        assertEquals(1, callbacks.get(), "Only the outer completion request publishes results");
        return result.join();
    }

    private static byte[] type(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "EMPTY", "L" + name + ";", null, null).visitEnd();
        var getter = writer.visitMethod(Opcodes.ACC_PUBLIC, "getCount", "()I", null, null);
        getter.visitCode();
        getter.visitInsn(Opcodes.ICONST_0);
        getter.visitInsn(Opcodes.IRETURN);
        getter.visitMaxs(1, 1);
        getter.visitEnd();
        var clear = writer.visitMethod(Opcodes.ACC_PUBLIC, "clear", "()V", null, null);
        clear.visitCode(); clear.visitInsn(Opcodes.RETURN); clear.visitMaxs(0, 1); clear.visitEnd();
        var setter = writer.visitMethod(Opcodes.ACC_PUBLIC, "setCount", "(I)V", null, null);
        setter.visitParameter("count", 0);
        setter.visitCode(); setter.visitInsn(Opcodes.RETURN); setter.visitMaxs(0, 2); setter.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] program() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, JavaSnippetSource.PROGRAM_TYPE.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"}).visitEnd();
        writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "noResult", "()Ljava/lang/Object;", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "log", "(Ljava/lang/Object;)V", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "output", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
