package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.GlobalConfig;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.DebuggerEditorPresentation;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DebuggerInspectorTest {
    private static final DebugEngine.StackFrame FRAME = new DebugEngine.StackFrame(
            7,
            "Target.run",
            "example.Target",
            URI.create("decompiled:///example/Target.java"),
            21,
            3
    );

    @Test
    void evaluatesAutomaticExpressionsOncePerFrameRevision() throws Exception {
        GlobalConfig config = GlobalConfig.getInstance();
        InstanceState state = InstanceState.inMemory();
        boolean previousPreviews = config.automaticDebuggerPreviews();
        AtomicInteger inspections = new AtomicInteger();
        AtomicInteger explicitEvaluations = new AtomicInteger();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        try {
            state.setDebuggerWatches(List.of("counter()"));
            config.setAutomaticDebuggerPreviews(true);
            SwingUtilities.invokeAndWait(() -> {
                DebuggerInspector inspector = new DebuggerInspector(state,
                        controller,
                        target -> {
                        },
                        runtime(inspections, explicitEvaluations, List.of())
                );
                inspector.beginFrame(FRAME);
                inspector.showVariables(FRAME, List.of());
                inspector.showVariables(FRAME, List.of());
                config.setAutomaticDebuggerPreviews(false);
                config.setAutomaticDebuggerPreviews(true);
                assertEquals(1, inspections.get(), "A repaint re-ran an automatic watch");

                JavaExpressionField expression = find(inspector, JavaExpressionField.class);
                assertNotNull(expression);
                expression.setText("nextValue()");
                expression.postActionEvent();
                config.setAutomaticDebuggerPreviews(false);
                assertEquals(1, explicitEvaluations.get(), "Explicit Enter evaluation ran more than once");
                assertEquals(1, inspections.get(), "Explicit evaluation was immediately inspected again");

                inspector.beginFrame(FRAME);
                inspector.showVariables(FRAME, List.of());
                assertEquals(1, inspections.get(),
                        "Selecting the same frame must not repeat a watch or an explicit evaluation");
                inspector.close();
            });
        } finally {
            config.setAutomaticDebuggerPreviews(previousPreviews);
            controller.close();
            DebuggerEditorPresentation.clear();
        }
    }

    @Test
    void publishesOneEditorSnapshotAfterAllRootPreviewsResolve() throws Exception {
        GlobalConfig config = GlobalConfig.getInstance();
        boolean previousPreviews = config.automaticDebuggerPreviews();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        AtomicInteger publications = new AtomicInteger();
        try {
            config.setAutomaticDebuggerPreviews(true);
            DebuggerEditorPresentation.clear();
            Runnable removeListener = DebuggerEditorPresentation.addListener(snapshot -> publications.incrementAndGet());
            publications.set(0);
            SwingUtilities.invokeAndWait(() -> {
                DebuggerInspector inspector = new DebuggerInspector(InstanceState.inMemory(),
                        controller,
                        target -> {
                        },
                        runtime(new AtomicInteger(), new AtomicInteger(), List.of())
                );
                inspector.beginFrame(FRAME);
                inspector.showVariables(FRAME, List.of(
                        variable("first", 11, 1),
                        variable("second", 12, 1),
                        variable("third", 13, 1)
                ));
                assertEquals(1, publications.get());
                inspector.close();
            });
            removeListener.run();
        } finally {
            config.setAutomaticDebuggerPreviews(previousPreviews);
            controller.close();
            DebuggerEditorPresentation.clear();
        }
    }

    @Test
    void retainsExpressionPreviewAcrossUnrelatedTreeRebuilds() throws Exception {
        GlobalConfig config = GlobalConfig.getInstance();
        boolean previousPreviews = config.automaticDebuggerPreviews();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        AtomicInteger previewRequests = new AtomicInteger();
        try {
            config.setAutomaticDebuggerPreviews(true);
            SwingUtilities.invokeAndWait(() -> {
                DebuggerInspector.RuntimeAccess runtime = new DebuggerInspector.RuntimeAccess() {
            @Override public CompletableFuture<com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease> retainValue(String pauseId, int reference) {
                return CompletableFuture.completedFuture(com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease.NONE);
            }
                    @Override
                    public CompletableFuture<List<DebugEngine.Variable>> variables(
                            DebugEngine.StackFrame frame,
                            int variablesReference,
                            int start,
                            int count
                    ) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    @Override
                    public CompletableFuture<DebugEngine.ValuePreview> preview(
                            DebugEngine.StackFrame frame,
                            int variablesReference
                    ) {
                        previewRequests.incrementAndGet();
                        return CompletableFuture.completedFuture(
                                new DebugEngine.ValuePreview("rendered", "rendered detail")
                        );
                    }

                    @Override
                    public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
                            String expression,
                            DebugEngine.StackFrame frame
                    ) {
                        return CompletableFuture.completedFuture(
                                new DebugEngine.EvaluationResult("Value@1", "example.Value", 77, 0)
                        );
                    }

                    @Override
                    public CompletableFuture<DebugEngine.EvaluationResult> inspect(
                            String expression,
                            DebugEngine.StackFrame frame
                    ) {
                        return CompletableFuture.completedFuture(
                                new DebugEngine.EvaluationResult("1", "int", 0, 0)
                        );
                    }
                };
                DebuggerInspector inspector = new DebuggerInspector(InstanceState.inMemory(), controller, target -> {
                }, runtime);
                inspector.beginFrame(FRAME);
                inspector.showVariables(FRAME, List.of());

                JavaExpressionField expression = find(inspector, JavaExpressionField.class);
                assertNotNull(expression);
                expression.setText("value()");
                expression.postActionEvent();
                assertEquals("rendered", expressionValue(inspector).preview().summary());

                inspector.addWatch("counter");
                assertEquals("rendered", expressionValue(inspector).preview().summary());
                assertEquals(1, previewRequests.get(), "A rebuild requested the same expression preview again");
                inspector.close();
            });
        } finally {
            config.setAutomaticDebuggerPreviews(previousPreviews);
            controller.close();
            DebuggerEditorPresentation.clear();
        }
    }

    @Test
    void loadsLargeChildrenInExplicitBoundedPages() throws Exception {
        GlobalConfig config = GlobalConfig.getInstance();
        boolean previousPreviews = config.automaticDebuggerPreviews();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        List<Integer> starts = new ArrayList<>();
        try {
            config.setAutomaticDebuggerPreviews(false);
            SwingUtilities.invokeAndWait(() -> {
                DebuggerInspector.RuntimeAccess runtime = new DebuggerInspector.RuntimeAccess() {
            @Override public CompletableFuture<com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease> retainValue(String pauseId, int reference) {
                return CompletableFuture.completedFuture(com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease.NONE);
            }
                    @Override
                    public CompletableFuture<List<DebugEngine.Variable>> variables(
                            DebugEngine.StackFrame frame,
                            int variablesReference,
                            int start,
                            int count
                    ) {
                        starts.add(start);
                        int end = Math.min(450, start + count);
                        List<DebugEngine.Variable> values = new ArrayList<>();
                        for (int index = start; index < end; index++) {
                            values.add(variable("[" + index + "]", 0, 0));
                        }
                        return CompletableFuture.completedFuture(values);
                    }

                    @Override
                    public CompletableFuture<DebugEngine.ValuePreview> preview(
                            DebugEngine.StackFrame frame,
                            int variablesReference
                    ) {
                        return CompletableFuture.completedFuture(DebugEngine.ValuePreview.NONE);
                    }

                    @Override
                    public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
                            String expression,
                            DebugEngine.StackFrame frame
                    ) {
                        return CompletableFuture.failedFuture(new AssertionError("Unexpected evaluation"));
                    }

                    @Override
                    public CompletableFuture<DebugEngine.EvaluationResult> inspect(
                            String expression,
                            DebugEngine.StackFrame frame
                    ) {
                        return CompletableFuture.failedFuture(new AssertionError("Unexpected inspection"));
                    }
                };
                DebuggerInspector inspector = new DebuggerInspector(InstanceState.inMemory(), controller, target -> {
                }, runtime);
                inspector.beginFrame(FRAME);
                inspector.showVariables(FRAME, List.of(variable("items", 50, 450)));

                JTree tree = find(inspector, JTree.class);
                assertNotNull(tree);
                tree.expandRow(0);
                DefaultMutableTreeNode owner = (DefaultMutableTreeNode) tree.getPathForRow(0)
                        .getLastPathComponent();
                assertEquals(201, owner.getChildCount());
                DefaultMutableTreeNode more = (DefaultMutableTreeNode) owner.getLastChild();
                assertInstanceOf(DebuggerValueTree.MoreChildren.class, more.getUserObject());

                tree.expandPath(new TreePath(more.getPath()));
                assertEquals(List.of(0, 200), starts);
                assertEquals(401, owner.getChildCount());
                more = (DefaultMutableTreeNode) owner.getLastChild();
                tree.expandPath(new TreePath(more.getPath()));
                assertEquals(List.of(0, 200, 400), starts);
                assertEquals(450, owner.getChildCount());
                inspector.close();
            });
        } finally {
            config.setAutomaticDebuggerPreviews(previousPreviews);
            controller.close();
            DebuggerEditorPresentation.clear();
        }
    }

    @Test
    void loadsNamedObjectFieldsOnceWithoutPagingArguments() throws Exception {
        GlobalConfig config = GlobalConfig.getInstance();
        boolean previousPreviews = config.automaticDebuggerPreviews();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        List<List<Integer>> requests = new ArrayList<>();
        try {
            config.setAutomaticDebuggerPreviews(false);
            SwingUtilities.invokeAndWait(() -> {
                DebuggerInspector.RuntimeAccess runtime = new DebuggerInspector.RuntimeAccess() {
            @Override public CompletableFuture<com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease> retainValue(String pauseId, int reference) {
                return CompletableFuture.completedFuture(com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease.NONE);
            }
                    @Override
                    public CompletableFuture<List<DebugEngine.Variable>> variables(
                            DebugEngine.StackFrame frame,
                            int variablesReference,
                            int start,
                            int count
                    ) {
                        requests.add(List.of(start, count));
                        return CompletableFuture.completedFuture(List.of(variable("field", 0, 0)));
                    }

                    @Override
                    public CompletableFuture<DebugEngine.ValuePreview> preview(
                            DebugEngine.StackFrame frame,
                            int variablesReference
                    ) {
                        return CompletableFuture.completedFuture(DebugEngine.ValuePreview.NONE);
                    }

                    @Override
                    public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
                            String expression,
                            DebugEngine.StackFrame frame
                    ) {
                        return CompletableFuture.failedFuture(new AssertionError("Unexpected evaluation"));
                    }

                    @Override
                    public CompletableFuture<DebugEngine.EvaluationResult> inspect(
                            String expression,
                            DebugEngine.StackFrame frame
                    ) {
                        return CompletableFuture.failedFuture(new AssertionError("Unexpected inspection"));
                    }
                };
                DebuggerInspector inspector = new DebuggerInspector(InstanceState.inMemory(), controller, target -> {
                }, runtime);
                inspector.beginFrame(FRAME);
                inspector.showVariables(FRAME, List.of(new DebugEngine.Variable(
                        "object", "object", "object", "Value", "example.Value",
                        DebugEngine.VariableKind.LOCAL, 1, 60, 4, 0
                )));

                JTree tree = find(inspector, JTree.class);
                assertNotNull(tree);
                tree.expandRow(0);
                assertEquals(List.of(List.of(0, 0)), requests);
                DefaultMutableTreeNode owner = (DefaultMutableTreeNode) tree.getPathForRow(0)
                        .getLastPathComponent();
                assertEquals(1, owner.getChildCount());
                inspector.close();
            });
        } finally {
            config.setAutomaticDebuggerPreviews(previousPreviews);
            controller.close();
            DebuggerEditorPresentation.clear();
        }
    }

    private static DebuggerInspector.RuntimeAccess runtime(
            AtomicInteger inspections,
            AtomicInteger evaluations,
            List<DebugEngine.Variable> variables
    ) {
        return runtime(inspections, evaluations, variables, 0,
                ignored -> CompletableFuture.completedFuture(DebuggerValueLease.NONE));
    }

    private static DebuggerInspector.RuntimeAccess runtime(
            AtomicInteger inspections,
            AtomicInteger evaluations,
            List<DebugEngine.Variable> variables,
            int resultReference,
            java.util.function.IntFunction<CompletableFuture<DebuggerValueLease>> retain
    ) {
        return new DebuggerInspector.RuntimeAccess() {
            @Override
            public CompletableFuture<DebuggerValueLease> retainValue(String pauseId, int reference) {
                return retain.apply(reference);
            }
            @Override
            public CompletableFuture<List<DebugEngine.Variable>> variables(
                    DebugEngine.StackFrame frame,
                    int variablesReference,
                    int start,
                    int count
            ) {
                return CompletableFuture.completedFuture(variables);
            }

            @Override
            public CompletableFuture<DebugEngine.ValuePreview> preview(
                    DebugEngine.StackFrame frame,
                    int variablesReference
            ) {
                return CompletableFuture.completedFuture(new DebugEngine.ValuePreview("preview", "preview"));
            }

            @Override
            public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
                    String expression,
                    DebugEngine.StackFrame frame
            ) {
                evaluations.incrementAndGet();
                return CompletableFuture.completedFuture(new DebugEngine.EvaluationResult("value", "Object", resultReference, 0));
            }

            @Override
            public CompletableFuture<DebugEngine.EvaluationResult> inspect(
                    String expression,
                    DebugEngine.StackFrame frame
            ) {
                inspections.incrementAndGet();
                return CompletableFuture.completedFuture(new DebugEngine.EvaluationResult("1", "int", 0, 0));
            }
        };
    }

    @Test
    void replacingAndClosingAnInspectorReleasesValuesIncludingLateResults() throws Exception {
        GlobalConfig config = GlobalConfig.getInstance();
        boolean previousPreviews = config.automaticDebuggerPreviews();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        AtomicInteger owners = new AtomicInteger();
        AtomicInteger retains = new AtomicInteger();
        CompletableFuture<DebuggerValueLease> late = new CompletableFuture<>();
        try {
            config.setAutomaticDebuggerPreviews(false);
            SwingUtilities.invokeAndWait(() -> {
                var runtime = runtime(new AtomicInteger(), new AtomicInteger(), List.of(), 77, reference -> {
                    assertEquals(77, reference);
                    if (retains.incrementAndGet() == 3) return late;
                    owners.incrementAndGet();
                    return CompletableFuture.completedFuture(owners::decrementAndGet);
                });
                DebuggerInspector inspector = new DebuggerInspector(InstanceState.inMemory(), controller, ignored -> { }, runtime);
                try {
                    inspector.beginFrame(FRAME);
                    inspector.showVariables(FRAME, List.of());
                    JavaExpressionField expression = find(inspector, JavaExpressionField.class);
                    assertNotNull(expression);
                    expression.setText("first()");
                    expression.postActionEvent();
                    assertEquals(1, owners.get());
                    expression.setText("second()");
                    expression.postActionEvent();
                    assertEquals(1, owners.get(), "Replacing an expression must release the old value");
                    expression.setText("third()");
                    expression.postActionEvent();
                    assertEquals(0, owners.get());
                    inspector.close();
                    owners.incrementAndGet();
                    late.complete(owners::decrementAndGet);
                    assertEquals(0, owners.get(), "A result arriving after Close must release its value");
                } finally {
                    inspector.close();
                }
            });
        } finally {
            config.setAutomaticDebuggerPreviews(previousPreviews);
            controller.close();
            DebuggerEditorPresentation.clear();
        }
    }

    private static DebugEngine.Variable variable(String name, int reference, int indexedVariables) {
        return new DebugEngine.Variable(
                name,
                name,
                name,
                "value",
                "example.Value",
                DebugEngine.VariableKind.LOCAL,
                1,
                reference,
                0,
                indexedVariables
        );
    }

    private static DebuggerValueTree.DebugValue expressionValue(DebuggerInspector inspector) {
        JTree tree = find(inspector, JTree.class);
        assertNotNull(tree);
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int index = 0; index < root.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(index);
            if (child.getUserObject() instanceof DebuggerValueTree.ExpressionValue value && !value.watch()) {
                return value.value();
            }
        }
        throw new AssertionError("No retained expression result was rendered");
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = find(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
