package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionProposal;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Window;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExpressionCompletionSupportUiTest {
    @Test
    void opensCompletionForAnEvaluateField() throws Exception {
        withPopupFactory(ignored -> {
            Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
            try {
                onEdt(() -> fixture.field().setText("f"));
                await(fixture.completion()::isCompletionVisible);

                onEdt(() -> {
                    assertEquals("f", fixture.field().getText());
                    assertTrue(fixture.completion().isCompletionVisible());
                    invokeFieldAction(fixture.field(), "TAB");
                    assertEquals("false", fixture.field().getText());
                    assertFalse(fixture.completion().isCompletionVisible());
                });
            } finally {
                onEdt(fixture::close);
            }
        });
    }

    @Test
    void keepsBreakpointEditorOpenWhenCompletionAppears() throws Exception {
        withPopupFactory(ignored -> {
            Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::breakpointFixture);
            try {
                onEdt(() -> fixture.field().setText("f"));
                await(() -> fixture.completion().isCompletionVisible() || !fixture.parentPopup().isVisible());

                onEdt(() -> {
                    assertEquals("f", fixture.field().getText());
                    assertTrue(fixture.parentPopup().isVisible(), "Typing must not dismiss the breakpoint editor");
                    assertTrue(fixture.completion().isCompletionVisible(), "Completion must be visible beside the editor");
                    fixture.field().setText("t");
                });
                await(fixture.completion()::isCompletionVisible);
                onEdt(() -> {
                    assertTrue(
                            fixture.parentPopup().isVisible(),
                            "Continuing to type must not dismiss the breakpoint editor"
                    );
                    invokeFieldAction(fixture.field(), "TAB");
                    assertEquals("true", fixture.field().getText());
                    assertTrue(fixture.parentPopup().isVisible(), "Accepting completion must keep the editor open");
                });
            } finally {
                onEdt(fixture::close);
            }
        });
    }

    @Test
    void ignoresAStaleAsynchronousCompletionResponse() throws Exception {
        withPopupFactory(ignored -> {
            Fixture fixture = onEdt(ExpressionCompletionSupportUiTest::evaluateFixture);
            try {
                List<CompletableFuture<List<DebuggerCompletionProposal>>> requests = new java.util.ArrayList<>();
                List<String> requestTexts = new java.util.ArrayList<>();
                onEdt(() -> fixture.completion().setCompletionProvider((text, caret, explicit) -> {
                    CompletableFuture<List<DebuggerCompletionProposal>> request = new CompletableFuture<>();
                    requests.add(request);
                    requestTexts.add(text);
                    return request;
                }));

                onEdt(() -> fixture.field().setText("f"));
                await(() -> requestTexts.contains("f"));
                onEdt(() -> fixture.field().setText("t"));
                await(() -> requestTexts.contains("t"));
                int currentRequest = requestTexts.lastIndexOf("t");
                int staleRequest = requestTexts.lastIndexOf("f");

                onEdt(() -> requests.get(currentRequest).complete(List.of(new DebuggerCompletionProposal(
                        "true", "true", DebuggerCompletionProposal.Kind.KEYWORD,
                        "boolean literal", 0, 1
                ))));
                await(fixture.completion()::isCompletionVisible);
                assertTrue(onEdt(fixture.completion()::isCompletionVisible));
                onEdt(() -> requests.get(staleRequest).complete(List.of(new DebuggerCompletionProposal(
                        "false", "false", DebuggerCompletionProposal.Kind.KEYWORD,
                        "boolean literal", 0, 1
                ))));
                Thread.sleep(100);
                onEdt(() -> {
                    assertTrue(fixture.completion().isCompletionVisible());
                    invokeFieldAction(fixture.field(), "TAB");
                    assertEquals("true", fixture.field().getText());
                });
            } finally {
                onEdt(fixture::close);
            }
        });
    }

    private static Fixture evaluateFixture() {
        JFrame frame = frame();
        JTextField field = new JTextField(24);
        frame.add(field, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
        ExpressionCompletionSupport completion = completion(field);
        return new Fixture(frame, null, field, completion);
    }

    private static Fixture breakpointFixture() {
        JFrame frame = frame();
        JButton invoker = new JButton("Breakpoint");
        frame.add(invoker, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);

        JTextField field = new JTextField(24);
        JPopupMenu editor = new JPopupMenu();
        editor.add(field);
        editor.show(invoker, 0, invoker.getHeight());
        ExpressionCompletionSupport completion = completion(field);
        return new Fixture(frame, editor, field, completion);
    }

    private static JFrame frame() {
        JFrame frame = new JFrame();
        frame.setLayout(new BorderLayout());
        frame.setLocation(-20_000, -20_000);
        return frame;
    }

    private static ExpressionCompletionSupport completion(JTextField field) {
        ExpressionCompletionSupport completion = new ExpressionCompletionSupport(field);
        List<DebuggerCompletionProposal> proposals = List.of(new DebuggerCompletionProposal(
                "false", "false", DebuggerCompletionProposal.Kind.KEYWORD,
                "boolean literal", 0, 0, 5, 80
        ), new DebuggerCompletionProposal(
                "true", "true", DebuggerCompletionProposal.Kind.KEYWORD,
                "boolean literal", 0, 0, 4, 80
        ));
        completion.setCompletionProvider((text, caret, explicit) ->
                CompletableFuture.completedFuture(proposals.stream()
                        .filter(proposal -> proposal.label().startsWith(text.substring(0, Math.min(caret, text.length()))))
                        .map(proposal -> proposal.withRange(0, text.length()))
                        .toList()));
        return completion;
    }

    private static void withPopupFactory(CheckedConsumer<LayeredPopupFactory> test) throws Exception {
        PopupFactory previous = PopupFactory.getSharedInstance();
        LayeredPopupFactory factory = new LayeredPopupFactory();
        PopupFactory.setSharedInstance(factory);
        try {
            test.accept(factory);
        } finally {
            PopupFactory.setSharedInstance(previous);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (!onEdt(condition::getAsBoolean) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void invokeFieldAction(JTextField field, String keyStroke) {
        Object actionKey = field.getInputMap().get(KeyStroke.getKeyStroke(keyStroke));
        field.getActionMap().get(actionKey).actionPerformed(null);
    }

    private static void onEdt(Runnable action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    @FunctionalInterface
    private interface CheckedConsumer<T> {
        void accept(T value) throws Exception;
    }

    private record Fixture(
            JFrame frame,
            JPopupMenu parentPopup,
            JTextField field,
            ExpressionCompletionSupport completion
    ) implements AutoCloseable {
        @Override
        public void close() {
            this.completion.close();
            if (this.parentPopup != null) {
                this.parentPopup.setVisible(false);
            }
            this.frame.dispose();
        }
    }

    private static final class LayeredPopupFactory extends PopupFactory {
        @Override
        public Popup getPopup(Component owner, Component contents, int x, int y) {
            Component anchor = owner;
            if (anchor == null && contents instanceof JPopupMenu menu) {
                anchor = menu.getInvoker();
            }
            Window window = anchor instanceof Window candidate
                    ? candidate
                    : SwingUtilities.getWindowAncestor(anchor);
            JLayeredPane layeredPane = ((RootPaneContainer) window).getLayeredPane();
            Dimension size = contents.getPreferredSize();
            return new Popup() {
                @Override
                public void show() {
                    Container parent = contents.getParent();
                    if (parent != null) {
                        parent.remove(contents);
                    }
                    contents.setBounds(0, 0, size.width, size.height);
                    layeredPane.add(contents, JLayeredPane.POPUP_LAYER);
                }

                @Override
                public void hide() {
                    if (contents.getParent() == layeredPane) {
                        layeredPane.remove(contents);
                    }
                }
            };
        }
    }
}
