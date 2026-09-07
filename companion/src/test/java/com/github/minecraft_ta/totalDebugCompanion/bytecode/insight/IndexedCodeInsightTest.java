package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeSourceCatalog;
import com.github.minecraft_ta.totalDebugCompanion.search.insight.CodeInsightService;
import com.github.tth05.jindex.ClassIndex;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IndexedCodeInsightTest {
    private static final CodeSymbol.ClassSymbol ACTION = new CodeSymbol.ClassSymbol("fixture.Action");
    private static final CodeSymbol.MethodSymbol ACTION_RUN = new CodeSymbol.MethodSymbol(
            "fixture.Action", "run", "()V"
    );
    private static final CodeSymbol.MethodSymbol BASE_RUN = new CodeSymbol.MethodSymbol(
            "fixture.Base", "run", "()V"
    );
    private static final CodeSymbol.MethodSymbol CHILD_RUN = new CodeSymbol.MethodSymbol(
            "fixture.Child", "run", "()V"
    );

    @Test
    void summarizesUsagesImplementationsAndBaseMethods() {
        try (ClassIndex index = fixtureIndex()) {
            IndexedCodeInsight insight = new IndexedCodeInsight(index);
            Map<CodeSymbol, SymbolInsight> summaries = insight.summarize(List.of(
                    ACTION,
                    ACTION_RUN,
                    BASE_RUN,
                    CHILD_RUN
            ));

            assertEquals(
                    new SymbolInsight(4, List.of(new HierarchyFacet(HierarchyRelation.IMPLEMENTED_BY, 2))),
                    summaries.get(ACTION)
            );
            assertEquals(
                    new SymbolInsight(2, List.of(new HierarchyFacet(HierarchyRelation.IMPLEMENTED_BY, 2))),
                    summaries.get(ACTION_RUN)
            );
            assertEquals(
                    new SymbolInsight(0, List.of(
                            new HierarchyFacet(HierarchyRelation.OVERRIDDEN_BY, 1),
                            new HierarchyFacet(HierarchyRelation.IMPLEMENTS, 1)
                    )),
                    summaries.get(BASE_RUN)
            );
            assertEquals(
                    new SymbolInsight(0, List.of(
                            new HierarchyFacet(HierarchyRelation.IMPLEMENTS, 1),
                            new HierarchyFacet(HierarchyRelation.OVERRIDES, 1)
                    )),
                    summaries.get(CHILD_RUN)
            );
            assertEquals(HierarchyRelation.IMPLEMENTS, summaries.get(BASE_RUN).primaryGutterRelation().orElseThrow());
            assertEquals(HierarchyRelation.OVERRIDES, summaries.get(CHILD_RUN).primaryGutterRelation().orElseThrow());
        }
    }

    @Test
    void skipsMissingRuntimeMembersWithoutDiscardingValidInsights() {
        CodeSymbol.MethodSymbol missing = new CodeSymbol.MethodSymbol(
                "fixture.Action",
                "missing",
                "()V"
        );
        try (ClassIndex index = fixtureIndex()) {
            Map<CodeSymbol, SymbolInsight> summaries = new IndexedCodeInsight(index).summarize(List.of(
                    ACTION_RUN,
                    missing
            ));

            assertEquals(
                    new SymbolInsight(2, List.of(new HierarchyFacet(HierarchyRelation.IMPLEMENTED_BY, 2))),
                    summaries.get(ACTION_RUN)
            );
            assertFalse(summaries.containsKey(missing));
        }
    }

    @Test
    void returnsBoundedDeterministicHierarchyPages() {
        try (ClassIndex index = fixtureIndex()) {
            IndexedCodeInsight insight = new IndexedCodeInsight(index);

            HierarchyPage allClasses = insight.search(HierarchyQuery.implementations(ACTION), 10);
            assertEquals(
                    List.of("fixture.Base", "fixture.Child"),
                    allClasses.results().stream().map(result -> result.symbol().ownerClassName()).toList()
            );
            assertFalse(allClasses.truncated());

            HierarchyPage directClasses = insight.search(HierarchyQuery.implementations(ACTION, true), 10);
            assertEquals(List.of("fixture.Base"), directClasses.results().stream()
                    .map(result -> result.symbol().ownerClassName())
                    .toList());

            HierarchyPage implementations = insight.search(HierarchyQuery.implementations(ACTION_RUN), 1);
            assertEquals(List.of("fixture.Base"), implementations.results().stream()
                    .map(result -> result.symbol().ownerClassName())
                    .toList());
            assertEquals(true, implementations.truncated());

            HierarchyPage bases = insight.search(HierarchyQuery.baseMethods(CHILD_RUN), 10);
            assertEquals(
                    List.of("fixture.Action", "fixture.Base"),
                    bases.results().stream().map(result -> result.symbol().ownerClassName()).toList()
            );
        }
    }

    @Test
    void dispatchesInsightResultBackToTheEdt() throws Exception {
        try (ClassIndex index = fixtureIndex();
             CodeInsightService service = new CodeInsightService(() -> index, RuntimeSourceCatalog.empty())) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean callbackOnEdt = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            SwingUtilities.invokeAndWait(() -> service.summarize(List.of(ACTION_RUN), new CodeInsightService.Listener<>() {
                @Override
                public void onCompleted(Map<CodeSymbol, SymbolInsight> result) {
                    callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                    assertEquals(
                            new SymbolInsight(
                                    2,
                                    List.of(new HierarchyFacet(HierarchyRelation.IMPLEMENTED_BY, 2))
                            ),
                            result.get(ACTION_RUN)
                    );
                    completed.countDown();
                }

                @Override
                public void onFailed(Throwable cause) {
                    failure.set(cause);
                    completed.countDown();
                }
            }));

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(callbackOnEdt.get());
        }
    }

    @Test
    void keepsExistingConsumersUsableAfterRuntimeRebind() throws Exception {
        try (ClassIndex original = fixtureIndex();
             ClassIndex replacement = ClassIndex.fromBytes(List.of(actionClass()));
             CodeInsightService service = new CodeInsightService(() -> original, RuntimeSourceCatalog.empty())) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<HierarchyPage> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            service.rebind(() -> replacement, RuntimeSourceCatalog.empty());
            service.search(HierarchyQuery.implementations(ACTION), 10, new CodeInsightService.Listener<>() {
                @Override
                public void onCompleted(HierarchyPage page) {
                    result.set(page);
                    completed.countDown();
                }

                @Override
                public void onFailed(Throwable cause) {
                    failure.set(cause);
                    completed.countDown();
                }
            });

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(List.of(), result.get().results());
        }
    }

    private static ClassIndex fixtureIndex() {
        return ClassIndex.fromBytes(List.of(
                actionClass(),
                concreteClass("fixture/Base", "java/lang/Object", new String[]{"fixture/Action"}),
                concreteClass("fixture/Child", "fixture/Base", null),
                callerClass()
        ));
    }

    private static byte[] actionClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "fixture/Action",
                null,
                "java/lang/Object",
                null
        );
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] concreteClass(String name, String superName, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] callerClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Caller", null, "java/lang/Object", null);
        var method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "callTwice",
                "(Lfixture/Action;)V",
                null,
                null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "fixture/Action", "run", "()V", true);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "fixture/Action", "run", "()V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
